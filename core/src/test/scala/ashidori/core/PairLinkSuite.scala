package ashidori.core

import ashidori.core.sync.PairLink

class PairLinkSuite extends munit.FunSuite:
  private val Code = "7K3M9QXZ"

  test("parses the https link; the code is in the fragment"):
    assertEquals(PairLink.parse(s"https://sync.example.net/pair#c=$Code"), Some(PairLink("https://sync.example.net", Code)))
    assertEquals(PairLink.parse(s"https://Example.COM:8443/pair#c=$Code"), Some(PairLink("https://example.com:8443", Code)))

  test("refuses https links of any other shape"):
    for bad <- Seq(
        s"http://sync.example.net/pair#c=$Code",
        s"https://sync.example.net/pair?c=$Code",
        s"https://sync.example.net/pair/#c=$Code",
        s"https://sync.example.net/pairing#c=$Code",
        s"https://sync.example.net/x/pair#c=$Code",
        s"https://sync.example.net/pair?x=1#c=$Code",
        s"https://user@sync.example.net/pair#c=$Code",
        "https://sync.example.net/pair",
        "https://sync.example.net/pair#c=",
        "https://sync.example.net/pair#c=7k3m9qxz",
        "https://sync.example.net/pair#c=7K3M-9QXZ",
        "https://sync.example.net/pair#c=7K3M9QXZ0",
        "https://sync.example.net/pair#c=7K3M9QXI",
        s"https://sync.example.net/pair#code=$Code",
        s"https://sync.example.net/pair#c=$Code&r=XXXX",
        s"https:///pair#c=$Code"
      )
    do assertEquals(PairLink.parse(bad), None, bad)

  test("parses the custom-scheme link"):
    val enc = java.net.URLEncoder.encode("https://sync.example.net", "UTF-8")
    assertEquals(PairLink.parse(s"ashidori://pair?server=$enc&c=$Code"), Some(PairLink("https://sync.example.net", Code)))
    assertEquals(PairLink.parse(s"ashidori://pair?c=$Code&server=https://example.com/"), Some(PairLink("https://example.com", Code)))

  test("refuses custom-scheme links of any other shape"):
    for bad <- Seq(
        s"ashidori://pair?server=https://example.com",
        s"ashidori://pair?c=$Code",
        s"ashidori://pair?server=https://example.com&c=$Code&c=$Code",
        s"ashidori://pair?server=https://example.com&c=$Code&x=1",
        s"ashidori://pair?server=https://example.com/v1&c=$Code",
        s"ashidori://pair?server=https://example.com?a=1&c=$Code",
        s"ashidori://pair?server=ftp://example.com&c=$Code",
        s"ashidori://pair?server=http://example.com&c=$Code",
        s"ashidori://pair?server=https://example.com&c=abc",
        s"ashidori://other?server=https://example.com&c=$Code",
        s"ashidori://pair/x?server=https://example.com&c=$Code",
        s"ashidori://pair?server=https://example.com&c=$Code#f",
        s"intent://pair?server=https://example.com&c=$Code"
      )
    do assertEquals(PairLink.parse(bad), None, bad)

  test("http servers only when allowed (debug builds)"):
    val link = s"ashidori://pair?server=http%3A%2F%2F10.0.2.2%3A8787&c=$Code"
    assertEquals(PairLink.parse(link), None)
    assertEquals(PairLink.parse(link, allowHttp = true), Some(PairLink("http://10.0.2.2:8787", Code)))

  test("url and parse round-trip"):
    val u = PairLink.url("https://sync.example.net/", Code)
    assertEquals(u, s"https://sync.example.net/pair#c=$Code")
    assertEquals(PairLink.parse(u), Some(PairLink("https://sync.example.net", Code)))

  test("normalizeServer"):
    assertEquals(PairLink.normalizeServer(" https://Sync.Example.NET/ ", allowHttp = false), Some("https://sync.example.net"))
    assertEquals(PairLink.normalizeServer("https://sync.example.net/admin", allowHttp = false), None)
    assertEquals(PairLink.normalizeServer("http://localhost:8787", allowHttp = false), None)
    assertEquals(PairLink.normalizeServer("http://localhost:8787", allowHttp = true), Some("http://localhost:8787"))
