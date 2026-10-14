# Datalevin 1.2.2: native memory leak on cursor close

Closing an LMDB cursor that has scanned an entity stored in a sub-database
leaks about the size of that entity's datoms in native memory, every time.
Long-running processes grow by gigabytes; the Java heap stays flat.

## Reproduce

```sh
clojure -M:run 1000
```

Output on macOS 15 / arm64, Datalevin 1.2.2 (datalevin-embedded, bundled native library):

```
datoms: 901
RSS before: 507 MB
after   200 scan+close cycles: RSS   576 MB
after   400 scan+close cycles: RSS   664 MB
after   600 scan+close cycles: RSS   760 MB
after   800 scan+close cycles: RSS   861 MB
after  1000 scan+close cycles: RSS   949 MB
RSS after GC: 951 MB
```

Each cycle opens a cursor on the EAV list DBI, reads one entity's datom list
with `get-list*`, and closes the cursor. The entity has 900 multi-valued
strings of ~490 bytes, so LMDB keeps its duplicates in a sub-database. ~450 KB
leak per cycle; the memory is never returned.

## Cause

`dtlv_list_val_full_iter_seek` → `mdb_cursor_list_dup_subdb` copies the
entity's duplicate values into buffers stored on the cursor's **sub-cursor**
(`mc_xcursor`, fields at +0x408, +0x418, +0x428, +0x440 of the sub-cursor in
the macOS arm64 build; `mdb_cursor_leaf_cache_materialize` uses the same
fields for leaf-page entities). `mdb_cursor_close` frees those four fields on
the **main** cursor only and then frees the struct, so the sub-cursor's buffers
are orphaned. Found with macOS `malloc_history` after
`turn_on_stack_logging`, confirmed by disassembling `libdtlv.dylib`.

Two further consequences make it bite in real applications:

- Datalevin opens and closes a fresh cursor for every scan inside a write
  transaction and for every scan on a virtual thread, so each such scan of a
  wide entity leaks once.
- Read cursors are pooled in a per-thread `ThreadLocal` deque. A thread that
  terminates (virtual threads, retiring pool threads) takes its cursors with
  it unclosed, leaking the whole cursor and its buffers.

## Expected

`mdb_cursor_close` (and whatever releases cursors of ended threads) should
free the sub-cursor's cache buffers too; RSS after the loop should stay near
the "before" value.
