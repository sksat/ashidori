package ashidori.core

import ashidori.core.CommitIntent.*

class CommitIntentSuite extends munit.FunSuite:
  private val raw = CommitIntent(
    Seq(Target("imu/2026-10-19.parquet", 1000, 5000), Target("gnss_raw/2026-10-19.parquet", 200, 3000)),
    Delete.Raw(10, 20, 99, ArchiveMerge.Cursor(10, Long.MinValue), ArchiveMerge.Cursor(15, 42), Seq(3, 7)))

  test("records round-trip, and a record cut short is no record") {
    for i <- Seq(raw, CommitIntent(Seq(Target("fixes/2026-10-19.parquet", 5, 50)), Delete.Hot("fixes", 1, 2, 3)),
        CommitIntent(Nil, Delete.Ids(Seq(1, 2))), CommitIntent(Seq(Target("fixes/2026-09.parquet", 9, 90)),
          Delete.Files(Seq("fixes/2026-09-01.parquet", "fixes/2026-09-02.parquet")))) do
      assertEquals(decode(encode(i)), Some(i))
    assertEquals(decode(encode(raw).stripSuffix("end\n")), None)
    assertEquals(decode(""), None)
  }

  test("a commit is finished when every new file is in place or waits verified; else dropped") {
    val a = raw.files(0).path
    val b = raw.files(1).path
    // Died after renaming the first file: rename the second, then delete.
    assertEquals(recovery(raw, Map(a -> OnDisk(Some((1000, 5000)), None), b -> OnDisk(Some((150, 2500)), Some((200, 3000))))),
      Recovery.Finish(Seq(b)))
    // Died after both renames, before the deletion.
    assertEquals(recovery(raw, Map(a -> OnDisk(Some((1000, 5000)), None), b -> OnDisk(Some((200, 3000)), None))),
      Recovery.Finish(Nil))
    // Died before any rename (both verified tmp files there): finish it too.
    assertEquals(recovery(raw, Map(a -> OnDisk(None, Some((1000, 5000))), b -> OnDisk(Some((150, 2500)), Some((200, 3000))))),
      Recovery.Finish(Seq(a, b)))
    // Nothing published and a tmp file that is not the verified one: forget
    // it (nothing was renamed or deleted).
    assertEquals(recovery(raw, Map(a -> OnDisk(Some((900, 4000)), Some((10, 77))), b -> OnDisk(Some((150, 2500)), None))), Recovery.Drop)
    assertEquals(recovery(raw, Map.empty), Recovery.Drop)
    // One file published, the other's verified tmp gone: neither finished
    // nor undone; the record stays.
    assertEquals(recovery(raw, Map(a -> OnDisk(None, Some((10, 77))), b -> OnDisk(Some((200, 3000)), None))), Recovery.Stuck)
  }
