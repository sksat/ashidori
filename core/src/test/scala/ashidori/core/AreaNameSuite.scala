package ashidori.core

class AreaNameSuite extends munit.FunSuite:
  test("strips country, postal code, prefecture and block numbers") {
    assertEquals(AreaName.fromAddressLine("日本、〒154-0024 東京都世田谷区三軒茶屋２丁目１１−２"), Some("世田谷区三軒茶屋"))
    assertEquals(AreaName.fromAddressLine("日本、〒100-0004 東京都千代田区大手町1丁目"), Some("千代田区大手町"))
    assertEquals(AreaName.fromAddressLine("〒060-0005 北海道札幌市中央区北五条西4丁目"), Some("札幌市中央区北五条西"))
    assertEquals(AreaName.fromAddressLine("日本、〒530-0001 大阪府大阪市北区梅田3丁目1"), Some("大阪市北区梅田"))
    assertEquals(AreaName.fromAddressLine("日本、〒254-0000 神奈川県平塚市"), Some("平塚市"))
  }

  test("gives up when only the prefecture remains") {
    assertEquals(AreaName.fromAddressLine("日本、東京都"), None)
  }

  test("feature and road names after the area are dropped") {
    assertEquals(AreaName.fromAddressLine("日本、〒144-0041 東京都大田区羽田空港 羽田空港 (HND)"), Some("大田区羽田空港"))
    assertEquals(AreaName.fromAddressLine("日本、〒066-0012 北海道千歳市美々 道路"), Some("千歳市美々"))
    assertEquals(AreaName.fromAddressLine("日本、〒144-0041 東京都大田区羽田空港羽田空港"), Some("大田区羽田空港"))
  }

  test("from 位置参照情報 parts") {
    assertEquals(AreaName.fromParts("渋谷区", "宇田川町"), Some("渋谷区宇田川町"))
    assertEquals(AreaName.fromParts("渋谷区", "神宮前六丁目"), Some("渋谷区神宮前"))
    assertEquals(AreaName.fromParts("札幌市中央区", "北五条西一丁目"), Some("札幌市中央区北五条西"))
    assertEquals(AreaName.fromParts("北広島市", "フェア河増1丁目"), Some("北広島市フェア河増"))
    assertEquals(AreaName.fromParts("入間郡毛呂山町", "大字阿諏訪"), Some("毛呂山町阿諏訪"))
    assertEquals(AreaName.fromParts("上川郡比布町", "字近文五線"), Some("比布町近文五線"))
    assertEquals(AreaName.fromParts("赤穂郡上郡町", "大持"), Some("上郡町大持"))
  }

  test("municipality and city names") {
    assertEquals(AreaName.municipality("西多摩郡奥多摩町"), "奥多摩町")
    assertEquals(AreaName.municipality("郡山市"), "郡山市")
    assertEquals(AreaName.municipality("小郡市"), "小郡市")
    assertEquals(AreaName.municipality("大和郡山市"), "大和郡山市")
    assertEquals(AreaName.city("札幌市中央区"), "札幌市")
    assertEquals(AreaName.city("渋谷区"), "渋谷区")
    assertEquals(AreaName.city("四日市市"), "四日市市")
  }
