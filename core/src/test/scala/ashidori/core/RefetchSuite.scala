package ashidori.core

import scala.collection.mutable

class RefetchSuite extends munit.FunSuite:
  /** Jobs and timers run by hand, in the order the test says. */
  private final class Rig(results: mutable.Queue[Boolean]):
    val jobs = mutable.Queue.empty[() => Unit]
    val timers = mutable.Map.empty[Int, () => Unit]
    var nextTimer = 0
    val runs = mutable.ArrayBuffer.empty[(String, Seq[String])]
    val allowed = mutable.Set.empty[String]
    val r = Refetch[String, String](
      run = (k, rs) => { runs += k -> rs; results.dequeue() },
      allowed = allowed.contains,
      execute = f => jobs.enqueue(f),
      schedule = (_, f) => { val id = nextTimer; nextTimer += 1; timers(id) = f; () => timers -= id },
      delay = _ => 1L)
    def runJob(): Unit = jobs.dequeue()()
    def fireTimers(): Unit = timers.values.toSeq.foreach(_()) // as they fire
    def clearFired(): Unit = timers.clear()

  test("a failed fetch waits for its retry, the timer starts it again, success ends it") {
    val rig = Rig(mutable.Queue(false, true))
    rig.allowed += "map"
    rig.r.request("liberty", "map")
    assertEquals(rig.r.state("liberty"), "fetching")
    rig.runJob()
    assertEquals(rig.r.state("liberty"), "waiting")
    val fire = rig.timers.values.head
    rig.timers.clear()
    fire()
    assertEquals(rig.r.state("liberty"), "fetching")
    rig.runJob()
    assertEquals(rig.r.state("liberty"), "idle")
    assertEquals(rig.runs.size, 2)
  }

  test("a retry asked for while the fetch runs is not lost: it runs again right after the failure") {
    val rig = Rig(mutable.Queue(false, true))
    rig.allowed += "map"
    rig.r.request("liberty", "map")
    // The network comes back while the first fetch is still running.
    rig.r.retryNow()
    assertEquals(rig.r.state("liberty"), "fetching+again")
    rig.runJob()
    // Not waiting for a timer: started again at once.
    assertEquals(rig.r.state("liberty"), "fetching")
    assertEquals(rig.timers.size, 0)
    rig.runJob()
    assertEquals(rig.r.state("liberty"), "idle")
    assertEquals(rig.runs.size, 2)
  }

  test("a map that left the screen: its waiting retry is cancelled and does not run") {
    val rig = Rig(mutable.Queue(false))
    rig.allowed += "map"
    rig.r.request("liberty", "map")
    rig.runJob()
    assertEquals(rig.r.state("liberty"), "waiting")
    rig.r.forget("map")
    assertEquals(rig.r.state("liberty"), "idle")
    assertEquals(rig.timers.size, 0)
    rig.r.retryNow()
    assertEquals(rig.jobs.size, 0)
    assertEquals(rig.runs.size, 1)
  }

  test("a retry runs only for requesters still allowed (the grant ended without a forget)") {
    val rig = Rig(mutable.Queue(false, true))
    rig.allowed ++= Seq("map", "snapshot")
    rig.r.request("liberty", "map")
    rig.r.request("liberty", "snapshot")
    rig.runJob()
    rig.allowed -= "map"
    rig.r.retryNow()
    rig.runJob()
    assertEquals(rig.runs.last, "liberty" -> Seq("snapshot"))
    // No one allowed any more: the retry does not run.
    val rig2 = Rig(mutable.Queue(false))
    rig2.allowed += "map"
    rig2.r.request("liberty", "map")
    rig2.runJob()
    rig2.allowed -= "map"
    rig2.fireTimers()
    assertEquals(rig2.jobs.size, 0)
    assertEquals(rig2.r.state("liberty"), "idle")
  }

  test("a requester forgotten while its fetch runs: the failure does not wait for a retry") {
    val rig = Rig(mutable.Queue(false))
    rig.allowed += "map"
    rig.r.request("liberty", "map")
    rig.r.forget("map")
    rig.r.retryNow()
    rig.runJob()
    assertEquals(rig.r.state("liberty"), "idle")
    assertEquals(rig.timers.size, 0)
    assertEquals(rig.jobs.size, 0)
  }

  test("backoff: 5 s doubling to 5 min") {
    assertEquals((0 to 8).map(Refetch.backoff), Seq(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L))
  }
