# SUBFLOW — Dil Kullanımı Kuralları & Prensipleri (Master Referans)
> Bu dosya, projedeki TÜM dil/çeviri kurallarının tek toplu referansıdır.
> Diğer dosyalardaki (SPEC, UPDATE 01-04, MASTER_PROTOCOL) dil kuralları
> burada birleşik ve nihai haliyle durur.

---

## 1. SANSÜRSÜZLÜK (TEMEL KURAL, TARTIŞMASIZ)

- Argo, küfür, hakaret, ironi, dark mizah, espri, imalı ifadeler,
  cinsel gönderme — orijinalinde nasılsa Türkçede **birebir** kalır
- Sanitize etme **YASAK**
- Ebeveyn filtresi **YASAK**
- Yumuşatma **YASAK**
- Sansür **YASAK**
- Karşılığın şiddeti kaynağın şiddetine eşit olur; yumuşatılmış bir
  karşılık kabul edilmez
- **Terim otoritesi:** bir ifadenin Türkçe karşılığını tek bir yer
  belirler, `assets/mega_dictionary.json`. Başka hiçbir tablo onun
  seçtiği karşılığı "yumuşak" sayıp yeniden yazmaz.
  Örnek: "damn" → "kahretsin", "goddamn it" → "lanet olsun".
  Bunlar sözlüğün seçimidir; `SlangDictionary` yalnızca MT'nin
  yumuşattığı satırı onarır, sözlüğün kendi çıktısını onarmaz
- Komedi sahnesi komedi, hakaret hakaret, küfür küfür olarak kalır —
  sahne türüne göre çeviri karakteri değişmez

---

## 2. KARAKTER SESİ & TON KORUMASI

- Karakter sesi, diyalog tonu, mizah ritmi korunur
- Arketipe göre dil ayrımı:
  - Tsundere → kesik cümleler, savunmacı ton
  - Asil/kibar karakter → uzun, resmi cümleler
  - Rahat/omurgasız karakter → argo, kısaltmalar
  - Villain → soğuk, kontrollü, bazen aşırı kibar-tehditkar kontrast
- Sahne türüne göre cümle ritmi:
  - Aksiyon → kısa/sert
  - Duygusal → uzun/akıcı
  - Komedi → ritmik/beklenmedik yapı
- Bağlam Motoru: batch halinde çeviri — izole satır değil, bağlamlı işlem.
  Batch boyutu **25 cue** (`TranslationEngine.BATCH_SIZE`). Doküman daha önce
  10-15 diyordu; kod 25 kullanıyor ve **bu sayının gerekçesi hiçbir yerde
  belgelenmemiş** — ölçümle doğrulanmış bir değer olarak okunmamalı
- Episode boyunca karakter ismi ve konuşma kalıbı tutarlılığı korunur

### 2.1 — Kekeleme/Heceleme Korunması

Kaynak metinde karakterin heyecan/panik/utanma/şaşkınlık anında
kelimenin ilk harfini tekrarlayarak konuşması (kekeleme), çeviri
çıktısında **aynı yapıda** korunur.

```
Format: [İlk harf]-[Kelimenin tamamı]

Harf büyük/küçük yazımı, normal Türkçe yazım kurallarına göre
doğal olarak şekillenir — kekelemeye özel sabit bir kural yoktur:
  - Kelime cümle/satır başındaysa → büyük harf
  - Kelime cümle ortasındaysa → küçük harf
  - Özel isimse (Akaishi gibi) → zaten büyük harf

Örnekler:
"A-Akaishi-san?"
"D-bana bakma!"
"S-kes şunu!"
"Ç-çünkü...?"
```

> Not: İlk örnekte sadece "mı" eki kaldırılmıştır (Madde 3.3
> gereği, gerçek soru olmadığı için) — soru işareti (?) ve
> diğer noktalamalar değiştirilmemiştir, orijinal haliyle kalır.

**Kekeleme harfi hedef kelimeyle eşleşmek zorundadır.** Kekeleme
harfi kaynak dilden (JP/EN vb.) olduğu gibi taşınmaz — çeviri
tamamlandıktan SONRA, ortaya çıkan Türkçe kelimenin gerçek ilk
harfine göre yeniden üretilir.

```
YANLIŞ (kaynak dildeki kekeleme harfi taşınmış, çeviri kelimesiyle uyuşmuyor):
"D-bana bakma!"   ← "bana" B ile başlıyor, D ile değil
"C-çünkü...?"     ← "çünkü" Ç ile başlıyor, C ile değil
"A-ve..."         ← "ve" V ile başlıyor, A ile değil

DOĞRU:
"B-bana bakma!"
"Ç-çünkü...?"
"V-ve..."
```

Kekeleme harfi her zaman, kendinden hemen sonra gelen Türkçe
kelimenin gerçek ilk harfinden türetilir — kaynak dildeki
kekeleme hecesi/harfi hiçbir zaman doğrudan kopyalanmaz.

- Bu SADECE kaynakta gerçekten kekeleme olan satırlara uygulanır —
  rastgele/gereksiz yere hiçbir satıra eklenmez
- Kaynak dilde kekeleme işareti varsa (tekrarlanan hece/harf,
  duraklama tire işareti vb.) bu, hedef satırda birebir aynı
  formatla (harf-tire-kelime) yeniden üretilir
- İnsan çevirmenlerin yaptığı standart budur — mekanik/düz cümleye
  dönüştürülmesi (kekelemenin kaybolması) hatadır

---

## 3. GRAMER DOĞRULUĞU

### 3.1 — Genel Yeniden Yapılandırma
- Kaynak dilin sözdizimi birebir taşınmaz, Türkçe doğal cümle
  yapısına yeniden kurulur
- Pasif/aktif çatı dengesi Türkçenin aktif çatı tercihine göre ayarlanır
- Zamir yoğunluğu Türkçe normuna çekilir
- Türkçe makine çevirisi yaygın hata pattern'leri düzeltilir
- Fiil çekimi ve cümle yapısı normalize edilir

### 3.2 — Tekil/Çoğul Hitap Doğruluğu

Sahnedeki katılımcı sayısı takip edilir (`SceneParticipantTracker`
+ `AddresseeAnalyzer`). Honorific tespiti (-san, -kun, -sama vb.)
ve sahne katılımcı sayısı üzerinden hitap tekil mi çoğul mu
belirlenir.

**İki bağımsız katman:**

```
KATMAN 1 — Açık grup/çoğul işaretleri
"hepiniz", "herkes", "sizler", "tümünüz" gibi ifadeler tek kişiye
hitapta ASLA doğru değildir — otorite/saygı/rütbe durumunda BİLE.
Formaliteden tamamen bağımsız, her zaman uygulanır.

KATMAN 2 — Sen/Siz çekim tercihi
Bu katman SADECE formaliteye bakar:
  - RESMİ ilişki (-sama, -sensei, otorite/rütbe) → "siz" çekimi
    (sınız/siniz vb.) KORUNUR — tek kişiye saygıyla "siz" demek
    Türkçede doğru ve geçerli bir kullanımdır
  - SAMİMİ/BİLİNMİYOR ilişki → yanlışlıkla çoğul-resmi çekim
    çıkmışsa tekil forma düzeltilir (sınız→sın, siniz→sin vb.)
```

**Önemli ayrım:** "Siz" çekimi hem gerçek çoğula (birden fazla
kişi) hem tekil-saygıya (otorite/rütbe/resmi ilişki) doğru şekilde
uygulanabilir — kural buna dokunmaz. Sadece yanlışlıkla, samimi
1:1 diyalogda ortaya çıkan hatalı çoğul çekimi hedeflenir.

**Genellik:** Bu mantık hiçbir isme özel değil — generic regex
(`\w+[-\s](san|kun|chan|sama|senpai|sensei|dono)`) ile hangi
karakter olursa olsun aynı şekilde çalışır.

### 3.3 — "Mı/Mi" Soru Eki Doğruluğu

Bir karakter başka birinin ismini/isim+honorific'ini **şaşkınlık,
tanıma anı, garipseme tepkisiyle** söylediğinde — bu gerçek bir
evet/hayır sorusu DEĞİLDİR. Böyle durumlarda "mı/mi" eki
kullanılmaz.

```
YANLIŞ (mevcut hatalı davranış):
"Akaishi-san mı?"   ← şaşkınlık anı, ama soru eki eklenmiş

DOĞRU:
"Akaishi-san..."  veya  "Akaishi-san?!"  veya bağlama uygun
şaşkınlık ifadesi — gerçek bir soru sorulmuyor
```

**"Mı/mi" ekinin doğru kullanıldığı tek durum:** Konuşan kişi
gerçekten birden fazla olası kimlik/seçenek arasında ayrım
yapıyorsa (örn. "bizim Akaishi-san mı, yoksa öteki Akaishi-kun mu"
gibi net bir alternatif/belirsizlik ortaya konuyorsa) — bu durumda
soru gerçektir, "mı/mi" doğru ve kalır.

**Tespit mantığı:** Kaynak cümlede sadece isim + şaşkınlık
noktalaması/tonu varsa ve alternatif/karşılaştırma yapısı yoksa
(başka bir isim/seçenek belirtilmemişse), "mı/mi" gereksiz
sayılır ve kaldırılır. Kaynak cümlede açık bir "A mı, yoksa B mi"
karşılaştırması varsa dokunulmaz.

- Bu, makine/AI çevirisine özgü yaygın bir hatadır — kaynak
  dildeki soru-benzeri yapıyı (örn. Japonca ka/desu ka) bağlamdan
  bağımsız her zaman "mı/mi" ekiyle çevirme eğilimi
- İnsan çevirisinde bu hataya neredeyse hiç rastlanmaz çünkü
  bağlam (şaşkınlık mı gerçek soru mu) doğal olarak ayırt edilir

---

## 4. ONORİFİK POLİTİKASI

- -san, -kun, -chan, -sama, -senpai, -sensei, -dono gibi ekler
  **Türkçeleştirilmez**, olduğu gibi korunur (örn. "Akaishi-san")
- Honorific tipi aynı zamanda ilişkinin resmi/samimi
  sınıflandırmasında (bkz. Madde 3.2) kullanılır:
  - `-sama, -sensei, -dono` → RESMİ
  - `-kun, -chan` → SAMİMİ
  - `-san, -senpai` → BİLİNMİYOR (bağlama göre değişir)

---

## 5. KÜLTÜREL LOKALİZASYON — ATASÖZÜ, DEYİM & SÖYLEYİŞ

Bu madde üç ayrı kategoriyi kapsar, birbirine karıştırılmaz:

```
ATASÖZÜ   → Nesilden nesile geçen, sabit ve değişmez hikmet sözleri
            (örn. "Damlaya damlaya göl olur")

DEYİM     → Kalıplaşmış, mecazi anlam taşıyan sabit ifadeler
            (örn. "içi içine sığmamak", "eli ayağı dolaşmak")

SÖYLEYİŞ  → Halkın günlük doğal konuşma biçimi, illa sabit kalıp
            olması gerekmeyen ama "bize özgü" ifade tarzı
            (örn. bir duyguyu/durumu Türkçenin kendine has,
            doğal biçimde dile getirme şekli)
```

**Temel kural:** Kaynak dildeki (JP/EN/CN vb.) bir replik/ifade
çevrilirken, mümkün olduğunda **gerçekten var olan, bilinen bir
Türk atasözü/deyim/söyleyişi** kullanılır — uydurma/yapay bir
"eşdeğer ifade" üretilmez.

- Kaynaktaki ifade birebir/kelime kelime çevrilmez
- MegaDictionary'de kaynak dil ifadesi → gerçek Türk atasözü/deyim
  eşleşmesi olarak tutulur (uydurma değil, doğrulanmış kalıplar)
- Eşleşme bulunamazsa, ContextEngine anlamca en yakın **gerçek**
  söyleyiş biçimini arar — yeni bir ifade icat etmez
- İddia: Türkçede, hangi dilde hangi durum/replik olursa olsun,
  buna anlamca ve duygu olarak denk düşen bir milli söyleyiş
  vardır — pipeline bunu bulmayı hedefler, bulamadığında en yakın
  doğal Türkçe ifadeye yönelir (asla zorlama/yapay çeviri değil)
- Kelime oyunu/espri kaynak dilde anlamsızsa, aynı komik etkiyi
  yaratan YENİ bir Türkçe kelime oyunu üretilebilir (bu tek
  istisnadır — çünkü kelime oyunu doğası gereği dile özgüdür,
  atasözü/deyim gibi "hazır karşılığı" olmayabilir)

---

## 6. TERMİNOLOJİ TUTARLILIĞI

- Özel isim, yetenek/büyü ismi, teknik terim ilk çevrildiği
  şekilde tüm bölüm/sezon boyunca sabit kalır (running glossary)
- Tutarsızlık tespit edilirse (aynı terim farklı çevrilmiş)
  SemanticValidator uyarı loglar

### 6.1 — Tanrı/Allah Terim Politikası

Ücretsiz makine çevirmenleri İngilizce "God" ifadesini Türkçeye
düzenli olarak "Allah" diye çeviriyor. SubFlow bunu nötr terime
normalize eder: **"God" her zaman "Tanrı" olarak çıkar, "Allah"
olarak değil.**

- Çeviri hattının son adımı olarak her satıra uygulanır
  (`Localize.godToTanri`)
- Çekim ekleri korunur: "Allah'a" → "Tanrıya", "Allah'ın" →
  "Tanrının", "Allah'ım" → "Tanrım"
- Yalnızca kelimenin **başında** "Allah" varsa ve eki bilinen bir
  çekim ekiyse dönüştürülür. İçinde "allah" geçen deyimlere
  dokunulmaz: "inşallah", "maşallah", "vallahi",
  "Allahaısmarladık" olduğu gibi kalır
- Büyük/küçük harf durumu korunur (bağırma dahil: "ALLAH" → "TANRI")

> Bu kural kodda ve testte (`LocalizeTest`) vardı ama bu dokümanda
> yazılı değildi. Doküman "tüm dil kurallarının tek referansı"
> olduğu için buraya alındı.

---

## 7. SEMANTİK VALİDATÖR

- Anlamsız çeviri tespit edilirse farklı kaynak dille tekrar denenir
- Sanitize/yumuşatma tespit edilirse sözlükten sert karşılığı konur
- Gereksiz "mı/mi" eki tespit edilirse (Madde 3.3) kaldırılır
- Kekeleme yapısı kaynakta var ama hedefte kaybolmuşsa (Madde 2.1)
  yeniden uygulanır

---

## 8. KALİTE KATMANLARI (Pipeline Sırası)

```
Ham MT çıktısı (LibreTranslate)
    ↓
MegaDictionary       (argo/küfür/terim sözlüğü + atasözü/deyim eşleşmeleri)
    ↓
Bağlam Motoru        (context + karakter tutarlılığı + kekeleme tespiti)
    ↓
Gramer Fixer         (genel gramer + tekil/çoğul hitap + mı/mi doğrulaması)
    ↓
Semantik Validator   (anlam kontrolü, sanitize tespiti, son kontroller)
    ↓
FINAL .srt
```

> Ham MT çıktısı hiçbir zaman final olarak yazılmaz — her satır
> bu 4 katmandan geçmeden .srt'ye yazılamaz.

### 8.1 — İSTİSNA: Bu doküman yalnızca Türkçe hedef dil için geçerlidir

Yukarıdaki garanti ve bu dokümandaki **tüm** kalite kuralları
(MegaDictionary, sözlük/glossary, sansür sertleştirme, tekil/çoğul
hitap, mı/mi düzeltmesi, kekeleme, Tanrı/Allah politikası)
`targetLang == "tr"` koşuluna bağlıdır.

**Türkçe dışındaki hedef diller ham makine çevirisidir.** Yalnızca
dil-bağımsız biçimsel temizlikten (boşluk/noktalama, kelime
tekrarı, ilk harf büyütme) geçerler. Kalite katmanı uygulanmaz.

Bu bilinçli bir karardır, eksiklik değil: Almanca veya Rusça
çıktının kalitesi proje tarafından doğrulanamıyor, doğrulanamayan
bir şey kalite olarak sunulmuyor. Arayüz bu farkı kullanıcıya
dürüstçe göstermelidir.

> TR-dışı diller için bu dokümandaki hiçbir madde "uygulanmıyor"
> diye hata sayılmaz — kapsam dışıdırlar.

---

## DEĞİŞMEZ İLKE

Bu dosyadaki hiçbir kural, uygulamanın diğer temel prensipleriyle
(sıfır maliyet, kaynak sınırsızlığı, içerik kimlik doğrulama,
indirmeme ilkesi) çelişmez veya onları geçersiz kılmaz — dil
kuralları bu prensiplerin ÜZERİNE, çeviri kalitesi katmanı olarak
eklenir.
