package ashidori.core

import java.util.concurrent.{CountDownLatch, Executors, LinkedBlockingQueue, TimeUnit}
import ashidori.core.sync.*

class RegistrationMachineSuite extends munit.FunSuite:
  /** Storage in memory; `onSave` runs inside savePairing (may block or throw). */
  final class Mem extends RegistrationStorage:
    @volatile var stored: Option[PendingRegistration] = None
    @volatile var pairedWith: Option[String] = None
    @volatile var onSave: () => Unit = () => ()
    def load() = stored
    def store(p: Option[PendingRegistration]) = stored = p
    def busy = pairedWith.isDefined
    def savePairing(p: PendingRegistration, deviceId: String) =
      onSave()
      pairedWith = Some(deviceId)

  private def machine(mem: Mem, events: LinkedBlockingQueue[RegistrationEvent], local: java.util.concurrent.ExecutorService) =
    RegistrationMachine(mem, _ => "dev1", () => "token", e => events.put(e), local, Executors.newCachedThreadPool())

  /** Waits for `local` to run everything asked of it so far. */
  private def drain(local: java.util.concurrent.ExecutorService): Unit = local.submit((() => ()): Runnable).get(10, TimeUnit.SECONDS)

  private def next[A](events: LinkedBlockingQueue[RegistrationEvent])(pf: PartialFunction[RegistrationEvent, A]): A =
    var r: Option[A] = None
    while r.isEmpty do
      val e = events.poll(10, TimeUnit.SECONDS)
      assert(e != null, "no event")
      r = pf.lift(e)
    r.get

  test("a cancel that comes too late changes nothing: a failed save stays retryable and is reported (F2)") {
    val mem = Mem()
    val events = LinkedBlockingQueue[RegistrationEvent]()
    val local = Executors.newSingleThreadExecutor()
    val m = machine(mem, events, local)
    val saving = CountDownLatch(1)
    val release = CountDownLatch(1)
    mem.onSave = () =>
      saving.countDown()
      release.await(10, TimeUnit.SECONDS)
      throw java.io.IOException("disk full")
    m.start("https://a.example", "pair", "code", "phone")
    // The answer is being taken in: the attempt is past cancelling.
    assert(saving.await(10, TimeUnit.SECONDS))
    assert(!m.cancel())
    release.countDown()
    val (attempt, msg) = next(events) { case RegistrationEvent.Failed(a, msg) => (a, msg) }
    drain(local)
    // The failure is reported to the screen (still the latest attempt)...
    assert(m.isLatest(attempt), s"attempt $attempt")
    assertEquals(msg, "disk full")
    // ...and the request is kept, with its token, for a retry.
    assertEquals(mem.stored.map(_.token), Some("token"))
    assertEquals(m.pending.map(_.token), Some("token"))
    assert(!m.state.sending)
    mem.onSave = () => ()
    m.retry()
    next(events) { case RegistrationEvent.Paired(_, _) => () }
    assertEquals(mem.pairedWith, Some("dev1"))
  }

  test("a cancel in time drops the attempt and removes the request") {
    val mem = Mem()
    val events = LinkedBlockingQueue[RegistrationEvent]()
    val local = Executors.newSingleThreadExecutor()
    val sent = CountDownLatch(1)
    val answer = CountDownLatch(1)
    val m = RegistrationMachine(mem, _ => { sent.countDown(); answer.await(10, TimeUnit.SECONDS); "dev1" }, () => "token",
      e => events.put(e), local, Executors.newCachedThreadPool())
    m.start("https://a.example", "pair", "code", "phone")
    assert(sent.await(10, TimeUnit.SECONDS))
    assert(m.cancel())
    answer.countDown()
    drain(local)
    Thread.sleep(100)
    drain(local)
    assertEquals(mem.stored, None)
    assertEquals(mem.pairedWith, None)
  }

  test("an account request keeps its invite and username on disk; older requests read without a username") {
    val p = PendingRegistration("https://sync.example.net", "account", "7K3M9QXZ0ABCDEFG", "phone", "token", "alice")
    assertEquals(PendingRegistration.fromBytes(p.toBytes), Some(p))
    val old = """{"url":"https://sync.example.net","kind":"account","code":"","name":"phone","token":"t"}"""
    assertEquals(PendingRegistration.fromBytes(old.getBytes("UTF-8")),
      Some(PendingRegistration("https://sync.example.net", "account", "", "phone", "t", "")))
  }

  test("a taken username drops the request and is reported as such") {
    val mem = Mem()
    val events = LinkedBlockingQueue[RegistrationEvent]()
    val local = Executors.newSingleThreadExecutor()
    val sent = LinkedBlockingQueue[PendingRegistration]()
    val m = RegistrationMachine(mem, p => { sent.put(p); throw UsernameTakenError(p.username) }, () => "token",
      e => events.put(e), local, Executors.newCachedThreadPool())
    m.start("https://sync.example.net", "account", "7K3M9QXZ0ABCDEFG", "phone", "alice")
    val (attempt, name) = next(events) { case RegistrationEvent.UsernameTaken(a, u) => (a, u) }
    assertEquals(name, "alice")
    assert(m.isLatest(attempt))
    assertEquals(sent.poll(1, TimeUnit.SECONDS).username, "alice")
    drain(local)
    // Final: the person chooses another username, with a new request.
    assertEquals(mem.stored, None)
    assertEquals(mem.pairedWith, None)
  }
