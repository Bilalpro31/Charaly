# CHARALY — PHASE 5 RAPORU: AI STORY LOOP + 2 DÜNYA RUNTIME TAKİBİ

Tarih: 2026-10-09 · Dal çalışma ağacı: `redesign-v5` (82 → 90 kirli/izlenmeyen dosya; hiçbiri geri alınmadı, üzerine yazılmadı; Git mutasyonu YOK)

---

## 1. Phase 5 sonucu

**Tamamlandı.** Üretim hikâye döngüsü gerçek sınıflar üzerinden izlendi, mimari sınır (LLM → doğrulanmış eylem → WorldState) doğrulandı, bulunan 4 gerçek kusur düzeltildi, 12 uçtan uca yolculuk gerçek `respond()` hattı üzerinden entegrasyon testleriyle kapsandı, kalıcılık/yeniden yükleme doğrulandı, tüm ilgili testler ve APK derlemesi koşuldu. İki takip işi (Story Pack uyumluluğu, SecretRevealed yetkisi) üretim akışına bağlandı ve regresyon testleriyle kilitlendi.

Aşağıdakiler **bilerek yapılmadı ve raporda ayrıldı**: GGUF model seçimi/indirme, llama.cpp, JNI, ağırlıklar ve performans ayarları (paralel görev alanına dokunulmadı); UI yeniden tasarımı; cihaz üzerinde doğrulama (cihaz yok).

---

## 2. İncelenen gerçek runtime zinciri

Gerçek yol (dosya/sınıf/fonksiyon adlarıyla, varsayım değil gözlem):

```
CharalyViewModel.send()                                   app/.../ui/CharalyViewModel.kt
→ CharalyRuntime.respond(instance, userInput, speaker)    charaly-runtime/.../session/CharalyRuntime.kt
→ ModelResidency.ensureResident(modelBinding)              (LAZY model yükü)
→ WorldDefinitionResolver.forInstance()                    (sabitlenmiş paket edisyonu)
→ SceneDirector.directScene() → commitScene()              director/SceneDirector.kt  (SceneStarted OLAYI ile, doğrudan state değil)
→ ContextBuilder.buildRequest()                            context/ContextBuilder.kt  (karakter-bilgisi bütçesi)
→ InferenceEngine.stream()                                 inference/InferenceEngine.kt  (LocalLlamaInferenceEngine | Mock/Scripted)
→ GenerationUpdate.Chunk (ham) → VM'de ProposedActionParser.stripActions ile gösterim
→ ProposedActionParser.parse(reply)                        director/ProposedAction.kt  (yalnız <charaly:action/> etiketleri)
→ ActionValidator.toPayload(action)                        (ActionReview.Accepted/Rejected)
→ EventEngine.applyImmediately(..., MODEL_PROPOSAL)        engine/EventEngine.kt  (validatePayload → mutate)
→ rememberTurn() → MemoryWriter (kapılar dahil)
→ repository.saveInstance(current)  ← TEK nokta, tur sonu
→ pollConditionalEvents() → advance() → ChapterPlanner.derive() → save
→ GenerationUpdate.Finished(instance = kalıcı snapshot)
```

Mimari sınır doğrulandı: model yalnızca **öneri** üretebilir; prose asla world-state mutasyonu sayılmaz (`stripActions` transcript'i etiketten arındırır; parse yalnız açık işaretçileri okur). Tüm kabul edilen eylemler `EventEngine.validatePayload → applyEvent` üzerinden (kaynak `EventOrigin.MODEL_PROPOSAL`, değişiklik günlüğünde görünür), retler `GenerationUpdate.RejectedAction` ile teşhis kanalında.

---

## 3. Bulunan sorunlar ve kök nedenleri

| # | Sorun | Kök neden | Durum |
|---|-------|-----------|-------|
| 1 | **Retry yanlış turu yeniden oynatıyordu**: kesilmiş gönderimden sonra Retry bir önceki turun girdisini yeniden gönderip o turun cevabını silerdi; kullanıcının yeni mesajı sessizce kaybolurdu | `retryGeneration` içindeki `lastUserEntry()` sezgisi; kesintiye uğrayan turun girdisi hiçbir yerde tutulmuyordu | Düzeltildi (`CharalyUiState.retryInput`) |
| 2 | **Regenerate/düzenleme aynı oyuncu mesajını iki kez yazıyordu** (transkripte `kullanıcı, kullanıcı, karakter`) | `prepareRegeneration`/`editLastUserMessage` yalnız karakter cevabını atarken `respond` kullanıcı satırını yeniden ekliyordu (`dropFrom(lastCharacter.turn)`) | Düzeltildi (`dropFrom(min(user, character))`) |
| 3 | **Ham eylem protokolü yayında görünüyordu**: streaming sırasında `<charaly:action .../>` token token ekrana yazılıyordu | `streamingText` filtresiz akümüle ediliyor; `NarrativeSegments` etiketi tanımıyordu. Ek: `<charaly:action/>` (özniteliksiz) ve kapanmamış etiket `stripActions`'tan kaçıyordu | Düzeltildi (gösterim eşiğinde `stripActions` + regex sertleştirildi) |
| 4 | **Kalıcılık hatasında tur yine de bitiyormuş gibi davranabiliyordu** (`Failed` sonrası türetilmiş tasarruflar ve `Finished`) | `saveInstance` başarısızlığı yutulup kitaplık/koşullu olay/saat ilerlemesi devam ediyordu; ekrandaki yanıt ile disk ayrışabilirdi | Düzeltildi (kalıcılık hatası terminal; `Finished` yok) |
| 5 | `SecretRevealed` kendi `revealedBy` iddiasına inanıyordu (Phase 4 bulgusu) | `validatePayload` açıklayıcının bilgi sahibi olduğunu hiç kontrol etmiyordu | Düzeltildi (Madde 6) |
| 6 | `compatibilityFor` story-open'da hiç çağrılmıyordu; üstelik `packFor`/`WorldDefinitionResolver` sabitlenmiş edisyon kaybolduğunda **aktif edisyona sessizce düşüyordu** (kanon ikamesi) | Uyumluluk sözleşmesi testlerde yaşıyor, giriş akışına bağlanmamıştı; fallback politikası `UNPINNED_LEGACY` ile sabitlenmiş kayıtlar için aynıydı | Düzeltildi (Madde 5 + `respond`'da sabit-edisyon koruması) |
| 7 | Bir tur içinde koşullu olaylar iki kez yoklanıyor (`respond` → `pollConditionalEvents` + `advance`→`pollConditionalEvents`) | Katmanlanmış tasarım; `repeatable`+`cooldownMinutes=0` bir olay aynı turda iki kez tetiklenebilir | **Dokümante edildi** (kural: mevcut deterministik olay/işleme semantiğini koruma talimatı gereği kasıtlı olarak yeniden düzenlenmedi) |
| 8 | `runCatching{...}.getOrDefault` ile yutulan kitaplık dışı hatalar (`advance`, `ChapterPlanner`, `touch`) | Kasıtlı: yazılmış bir tur kitaplık hatası yüzünden kaybedilmemeli | Dokümante edildi (artık risk) |

---

## 4. Yapılan kod değişiklikleri

**charaly-runtime (main)**
- `engine/EventEngine.kt` — `EventRejection.REVEALER_DOES_NOT_KNOW` (enum SONUNA ekli; mevcut sıralar korundu) + `SecretRevealed` doğrulamasında açıklayıcının `knowledge.knows(revealedBy, factId)` denetimi (mutasyondan ÖNCE).
- `domain/events/WorldEvent.kt` — `SecretRevealed` sözleşmesi belgelendi (iddia ≠ yetki).
- `director/ProposedAction.kt` — `TAG` regex `\s+`→`\b\s*` (özniteliksiz `<charaly:action/>` artık protokol sayılıp temizleniyor), yeni `OPEN_TAG` (kapanmamış etiket artığı temizleniyor), `stripActions` güncellendi.
- `session/CharalyRuntime.kt` — (a) `prepareRegeneration` çift mesaj hatası düzeltmesi; (b) `saveInstance` başarısızlığında tur terminal (`Failed(Persistence)` + tek çıkış); (c) **sabit edisyon koruması**: edisyon kaynağı bağlıyken sabitlenmiş sürüm cihazda yoksa `respond` `CharalyError.PackUnavailable` ile reddedilir (sessiz kanon ikamesi imkânsız); (d) yeni `CharalyError.PackUnavailable`.
- `domain/ConversationHistory.kt` — `dropFrom` belgesi gerçeğe uyarlandı.
- `storypack/StoryPackFormat.kt` — `PackOpenDecision` (Open/Blocked), `PackRecoveryAction` (IMPORT_ARCHIVE/OPEN_CATALOG), `PackCompatibilityReport.openDecision()` — mevcut `PackCompatibility` durumlarının üzerine ince bir karar katmanı (yeni uyumluluk enum'u YOK).
- `presentation/Loc.kt` — `error.pack_unavailable` (EN + TR, LocaleTest ikili sözleşme korundu).

**app (main)**
- `ui/CharalyViewModel.kt` — `retryInput` durumu + `retryGeneration` mantığı (kesik tur kendi girdisiyle yeniden); `editLastUserMessage` çift satır düzeltmesi; `Chunk` gösteriminde `stripActions`; `openStory(instanceId, onOpened)`/`continueMostRecent` uyumluluk kapısı (`blockIfUnplayable`) — kapı **touch'tan ÖNCE**; `dismissPackOpenGate()`.
- `ui/CharalyApp.kt` — `openStory` yardımcısı artık `onOpened` ile GEÇ GEZİNİR (kapı geçmediyse Stage hiç açılmaz); tek giriş noktası korunmuş üç çağrı yeri aynı yola alındı; `PackOpenGateDialog` her rotanın üstüne tek noktadan bağlandı (PackDeleteDialogs deseni); `state.lastCreatedStoryId` efekti ve `StoryRecord.onContinue` yardımcıya çevrildi.
- `ui/screens/PackStoreScreen.kt` — `PackOpenGateDialog` (Türkçe açıklama + yalnız gerçek kurtarma düğmeleri + "Kapat"; göç/migrasyon düğmesi bilerek YOK).
- `ui/packstore/StoryPackStore.kt` — `PackOpenGate`, `PackOpenAction` (etiketler eylemle birlikte), `PackOpenDecision.Blocked.toGateUi()` projeksiyonu (mühendislik jargonu sızıntısına karşı testli).
- `ui/ErrorMessages.kt` — `CharalyError.PackUnavailable` eşlemesi.

**Testler (yeni: 40 test)**
- `charaly-runtime/.../engine/SecretRevealAuthorityTest.kt` (10) — Madde 6 matrisi.
- `charaly-runtime/.../storypack/StoryOpenGateTest.kt` (8) — Madde 5 kararları, gerçek kurulum parçalarıyla.
- `charaly-runtime/.../integration/StoryLoopJourneysTest.kt` (15) — 12 yolculuk + 2 regresyon + fikstür kontrolü.
- `app/.../storypack/StoryOpenGateUiTest.kt` (7) — kapı UI sözleşmesi.
- Onarılan fikstürler: `domain/CausalGraphTest.kt` (3 sır testi), `integration/WorldRuntimeHardeningTest.kt` (Yolculuk 4) — açıklayıcı artık gerçeğe uygun şekilde sırrı bilen karakter; niyet korunup güçlendirildi (testler zayıflatılmadı).

Dokunulmayan ama yeniden koşulan regresyon alanları: parser/validator/olay motoru/bellek/akıl/kalıcılık/paket testleri (aşağıda).

---

## 5. Story Pack compatibility follow-up

**Bağlanan durumlar** (mevcut `PackCompatibility` sözleşmesi aynen kullanıldı):
- `COMPATIBLE` → aç (rapor: "Hikâye kendi paket sürümüyle (X) devam ediyor.").
- `UNPINNED_LEGACY` → aç (eski kayıtlar arka uyumlulukla çalışmaya devam eder; pinleme eski davranışı bozmaz).
- `MIGRATION_REQUIRED` → **açılmaz**; gerekçesi Türkçedir ("...Geçiş sunulmadan hikâye sessizce yeni sürüme taşınmaz.") + eylem: yalnız "Arşivden İçe Aktar" (doğru `.charaly-pack` sürümünü geri getirme) ve "Kapat". Metin "otomatik geçiş yapılmaz" der; **geçiş/migrasyon düğmesi yoktur** çünkü böyle bir işlem depoda tanımlı değildir (Madde 6 gereği otomatik geçiş de yoktur).
- `PACK_MISSING` → **açılmaz**; "Paket Mağazası" + "Arşivden İçe Aktar" + "Kapat"; kayıt-korunur sözü açıkça verilir.

**UI davranışı:** `CharalyViewModel.openStory`/`continueMostRecent` her açma/devam etmeden önce `packInstaller.compatibilityFor(instance)` çağırır; engellenirse hikâye açılmaz, **değiştirilmez ve touch edilmez** (son-oyunlanma damgası bile yazılmaz), `PackOpenGateDialog` Türkçe açıklama + gerçek kurtarma yollarını sunar. Gezinme `onOpened` ile kapı GEÇTİKTEN sonra yapılır (önceki davranışta Stage, kontrolsüz açılıyordu). İçe aktarma eşiği, arşivlerin her zaman geldiği gerçek seçiciyi (`packPicker`) açar.

**Ek savunma:** `respond` artık sabitlenmiş sürüm cihazda yokken (kuvvetli paket silme vb. uç durumda) üretim yapmayı `PackUnavailable` ile reddeder — `packFor`'un aktif edisyona sessiz düşüşü üretim yolunda da geçersiz.

**Testler (15):** sürüm mevcut+açılıyor; sürüm eksik paket var → `MIGRATION_REQUIRED` yalnız içe aktarma önerir; paket tamamen yok → `PACK_MISSING` katalog+içe aktarma; aktif edisyon pine asla ikame olmaz; yeniden başlatma kararı değiştirmez; başarısız karar StoryInstance'ı **hiç** değiştirmez (bütün nesne eşitliğiyle); serbest/eski kayıt yine açılır; üretim yolu da reddedilir + transkript kalıntısız; UI projeksiyonunda migrasyon düğmesi yoktur, açıklama sürümü ve "geçiş uygulanmış değildir" gerçeğini taşır, mühendislik jargonu (paketi adı, id@surum, yol) sızdırmaz.

---

## 6. SecretRevealed follow-up

**Kural (otoriter katmanda):** `EventEngine.validatePayload` içindeki `SecretRevealed` dalı, reddedilen olay `EventRejection.REVEALER_DOES_NOT_KNOW` üretir eğer `instance.knowledge.knows(payload.revealedBy, payload.factId)` yanlışsa. Kimlik/olgu çözümlenmesi yalnızca kanonik olay verisinden (`revealedBy`, `factId`); modelin "biliyorum" iddiasına veya prose'ye itibar edilmez. Kontrol, olayın **kabul edildiği andaki** karaktere özel bilgi durumuna karşı yapılır (`KnowledgeStore.knows`, bağlamsal sızıntı kapısıyla aynı oracle).

**Mevcut alanın meşru alternatif bilgi kaynakları korunur** (yenİ kural icat edilmedi): paket tohumlaması (`via = "story pack start"`), `KnowledgeDiscovered`, daha önceki bir ifşa — hepsi zaten `KnowledgeEntry` üretir ve dolayısıyla yetkilidir. Alan modeli tanıklık/gözlemi (`CharacterMind`) bilgi kaynağı olarak tanımlamadığından (bkz. `Fact` sözleşmesi: "Secrets require an explicit discovery event") masabaşında gözlem ifşa yetkisi VERMEZ; bu kasıtlı ve testlidir.

**Reddedilen ifşa hiçbir şey bırakmaz** (doğrulama mutasyondan önce): kimseye bilgi eklenmez, gizli bellek yazılmaz, sır "açıldı" diye işaretlenmez, hedef/pazarlık/ödül tetiklenmez, kuyrukta kalıntı kalmaz, kısmi mutasyon olmaz. Ret, `describe()` üzerinden "revealer does not know" diyerek teşhis kanalında görünür (sessiz başarı yok).

**Testler (10 + onarılan 3 + Yolculuk-4):** bilen karakter ifşa edebilir (olumlu); bilmeyen reddedilir; A'nın bilgisi B'yi yetkilendirmez; sır olmayan olgu da aynı kurala tabidir; reddedilen ifşa hedef/ödül hedeflerine kadar hiçbir aşağı akış etkisini tetiklemez (kanıt olarak aynı ifşa gerçek bilenle ödülle tamamlanır); `scheduleAndProcess` üzerinden kuyruk temiz kalır; kalıcılık+yeniden yükleme sonrası sahiplik ve kural doğruluğunu korur (bilerek zincirleme ifşa hâlâ yasaldır); ret nedenini adıyla bildirir. Kural **UI görünürlüğünde veya prompt talimatında değil**, otoriter `EventEngine` yolundadır.

---

## 7. End-to-end story journeys

Hepsi **sahte çıkarım (scripted/interrupted/exploding InferenceEngine)** ile, gerçek `CharalyRuntime.respond` hattı üzerindedir — gerçek GGUF SONUÇLARI DEĞİLDİR (aşağıya bkz. Madde 10). `StoryLoopJourneysTest` (15/15 geçti):

1. Salt anlatı: yalnız transkript değişir (ilişki/karakter/degisken/bilgi sabit; UTF-8 Türkçe bozulmasız). ✔
2. Geçerli eylem: `relate` parse→doğrula→olay→WorldState, kalıcı; sonraki turun bağlamı yeni ilişki değerini **yapısal** olarak görür; eylem tekrar uygulanmaz. ✔
3. Geçersiz eylem: izole Kule'ye yasadışı hareket motor reddi (`illegal movement` gerekçeli `RejectedAction`); dünya yasak değişikliği kazanmaz. ✔
4. Bozuk çıktı: 4 biçim (kapanmamış etiket, özniteliksiz etiket, geçersiz tip, boş) — mutasyon yok, çökme yok, protokol artığı gösterilmez/transkripte girmez. ✔
5. Karışık çıktı: Türkçe prose korunur, geçerli teklif uygulanır, geçersiz teklif teşhisli reddedilir (tam 1+1). ✔
6. Bilgi sınırı: tek karakter öğrenir; hem `knows` hem **prompt** düzeyinde sızıntı yok; başkasının sırrı hiçbir başkasının prompt'unda değil. ✔
7. İlişki güncellemesi gerçek dosya tabanlı kalıcılık + soğuk yeniden açılışta sağ. ✔
8. Hedef/olay etkileşimi: reddedilen ödeme hedefi **çözülmez** (ACTIVE kalır, tekrar denenebilir); sürekli reddedilen olaylar kuyruğu tıkamaz. ✔
9. Durdurma/iptal: yarım kalan tur **hiçbir şey** kaydetmez (yarım mutasyon/çift mesaj yok); yeniden deneme temiz. ✔ (Sahte motor `InferenceError.Cancelled` ile; gerçek durdurma bkz. Madde 10 `stress`.)
10. Retry/regenerate: kesik tur aksiyonları **bir kez** uygulanır; regenerate transkriptte tek oyuncu satırı bırakır (dünya politikası `dropFrom` sözleşmesiyle bilinçli: önceki turun dünya etkisi zaten gerçekleşmiştir). ✔
11. Kaydet/yeniden yükle: konuşma, bilgi, ilişkiler, olay/geçmiş kayıtları, bellek ve saat gösterilen dünyayla birebir. ✔
12. Çok turlu süreklilik: 3 ardışık tur; 3. turun bağlamı 1-2. turların sözlerini + o turların dünya değişimini içerir. ✔

Ek regresyonlar: streaming boyunca protokol maskesi; kapanmamış etiketin ne gösterildiği ne de uygulandığı.

---

## 8. Test sonuçları

| Komut | Sonuç | Sayım |
|---|---|---|
| `./gradlew :charaly-runtime:test :app:testDebugUnitTest --offline` | **SUCCESS** | `charaly-runtime` **1439/1439 geçti** (0 fail/0 err/0 skip); `app` **742/742 geçti** (0 fail/0 err/7 ATLANAN — bkz. aşağı) |
| `./gradlew :app:testDebugUnitTest --offline -PcharalyNativeLib=…/libcharaly_llama.so -PcharalyModelFile=/tmp/opencode/gguf/stories260K.gguf --tests RealStoryChatLoopTest` | 5 test, 4 geçti / 1 **başarısız** | Başarısız: `W – switching models…` — tek model koşusunda test, "model-B" yokluğunda **A'nın kendi dosyasını** B olarak kaydettiği için `path` eşitliği kaçınılmazdı (test kodundaki fallback artefaktı) |
| Aynı komut + `-PcharalyModelBFile=/tmp/opencode/gguf/stories15M-q4_0.gguf` | **SUCCESS** | `RealStoryChatLoopTest` **5/5 geçti** (gerçek ağırlıklarla) |
| Aynı iki modelli komut `--tests ActionProtocolModelTest` | **SUCCESS** | 1/1 geçti (21.3s; model gerçekten ayrıştırılabilir geçerli eylem etiketi üretti) |
| `./gradlew :app:testDebugUnitTest --tests StoryOpenGateUiTest` | **SUCCESS** | 7/7 |
| `./gradlew :app:assembleDebug --offline` | **SUCCESS** | APK üretildi |

Öne çıkan dosya bazlı sayımlar (hepsi 0 fail/0 err/0 skip): `SecretRevealAuthorityTest` 10 · `StoryOpenGateTest` 8 · `StoryLoopJourneysTest` 15 · `StoryOpenGateUiTest` 7 · `CausalGraphTest` 15 · `WorldRuntimeHardeningTest` 12 · `StoryPackVersionTest` 9 · `StoryChatLoopTest` 5 · `StoryEndToEndTest` 9 · `StoryPipelineIntegrationTest` 19 · `InvalidModelOutputMatrixTest` 18.

**ATLANAN testler ve gerekçeleri** (ilk yeşil koşuda 7 adet — hepsi kendi kendini atlayan gerçek-model testleri, `assumeTrue` ile dürüst atlıyorlar, geçmiyorlar):
- `RealStoryChatLoopTest` A/L/V/W/stress + `StoryLatencyBenchmarkTest` + `ActionProtocolModelTest` — `libcharaly_llama.so` + GGUF özellikleri verilmediği için. Sonrasındaki koşularda `RealStoryChatLoopTest`'in 5'i de GERÇEK ağırlıkla koşuldu ve geçti. `StoryLatencyBenchmarkTest` **bilerek koşulmadı**: latans/token performans ölçümü paralel görevin konusudur (Madde 0.3).
- Cihaz testi: **yok** (adb ile bağlı cihaz yok).

Not: ilk tam koşuda 2 XML yazım hatası (`PromptPrefixReuseTest`, `StoryLatencyDiagnosticTest` — paralel görevin testleri, o an dosya sistemi/yazıcı rekabeti) bir kez görüldü; sonraki iki tam koşuda tekrarlanmadı. Ara koşularda toplam 1439 testte 7 ve 1 başarısızlıkların **tamamı** yeni yazılan `StoryLoopJourneysTest` beklentilerindeydi (2 gerçek kod düzeltmesi: parser boş-etiket boşluğu ve `RELATE` hedefinin adla çözülmesi; 1 test beklenti hatası: `Relationship.affinity` alanı 50'den başlar) — doğrulama/test ayrımı saklıdır.

---

## 9. APK

- Yol: `/root/charaly/Charaly-debug-2026-10-09-phase5-story-loop.apk` (kaynak: `app/build/outputs/apk/debug/app-debug.apk`)
- Boyut: **92.330.157 bayt** (~88.0 MiB)
- Derleme: `./gradlew :app:assembleDebug --offline` → BUILD SUCCESSFUL (45 task).

---

## 10. Device/real-GGUF validation

**Gerçekten koşulan (gerçek GGUF, gerçek llama.cpp, gerçek JNI — host JVM):** mevcut `tools/native-harness/build/libcharaly_llama.so` (APK'nın gönderdiğiyle aynı `charaly_jni.cpp` derlemesi; **yeniden derlenmedi** — paralel göreve karışmamak için) + `/tmp/opencode/gguf/stories260K.gguf` ve `stories15M-q4_0.gguf` ile `RealStoryChatLoopTest`:

- `A – three real story turns with real tokens and real world mutations` → **GEÇTİ** (76.0s)
- `stress – ten real turns, a stop, a reload, and a restart` → **GEÇTİ** (898.2s; gerçek `stop()` dahil)
- `L – a Turkish story turn on real weights, with no mojibake` → **GEÇTİ** (429.5s; gerçek ağırlıkla UTF-8)
- `V – a registered model chats with no network` → **GEÇTİ** (39.0s)
- `W – switching models unloads the first…` → tek-model koşusunda testin kendi fallback artefaktı ile **BAŞARISIZ**; iki gerçek modelle **GEÇTİ** (2.0s). (Bu test ve `LocalModelResidency.switchTo` paralel görevin alanıdır; koda dokunulmadı, bulgu raporlandı.)

**Doğrulanmadı:** ART/bionic/Android düşük bellek öldürücüsü, sahne UI'ının dokunmatik davranışı, gerçek cihazda GGUF büyük model performansı — **cihaz bağlanmadığı için** (adb liste boş). `StoryLatencyBenchmarkTest` (latans ölçümü) bilerek atlandı. Bu koşular host doğrulamasıdır; harness'ın kendi uyarısı aynen geçerlidir: cihaz davranışı için cihaz gerekir.

---

## 11. Kalan riskler ve bilinen sınırlamalar

1. **`discover` gizli olgu için de kullanılabilir**: `ActionValidator.DISCOVER` (mevcut şema) bir karaktere herhangi bir *var olan* olgu hakkında bilgi verebilir — gizli olanlar dahil — ki bu `SecretRevealed`'ın açıklayıcı denetimini dolaylı atlatır. Mevcut şemayı keyfi daraltmamak için (Madde 0.8/2) dokunulmadı; Phase 6'da karar önerilir (aşağıda).
2. **Tek turda çift koşullu-olay yoklaması** (Madde 3 #7): `repeatable` + `cooldownMinutes=0` bir paket olayı aynı turda iki kez tetiklenebilir. Tek seferlik ve bekleme süreli olaylar güvende; mevcut determinizm/işleme kurallarını koruma talimatı gereği yeniden düzenlenmedi.
3. **Kitaplık sonrası enkaz**: `advance`/`ChapterPlanner`/`touch` hataları `runCatching` ile yutulur (kasıtlı; yazılmış tur korunur) ama yalnız sessizce — geliştirici telemetrisi eşiği yok.
4. **VM kapı akışı JVM'de birim-testlenemedi** (`AppPreferences(Context)` somut sınıf; Roboandroid/Robolectric bilinçli olarak yok). Kapı mantığı gerçek kurulum parçalarıyla (`StoryOpenGateTest`) ve saf projeksiyonla (`StoryOpenGateUiTest`) testli; ViewModel bağlantı noktası derleme + kod iziyle doğrulandı.
5. `blockIfUnplayable` nadiren de olsa **kontrolün kendisi** hata verirse eski davranışa (aç) döner — kilitli kalmayı tercih etmemek için kasıtlı.
6. 260K/15M oyuncak modellerle koşulan gerçek-model testleri kalite/saçmalık garantisi DEĞİL; testler bilinçli olarak modelin *sınırlarını* (metin geldi, ayrıştırıldı, doğrulandı, dünya yalnız kapıdan geçti) doğrular.
7. `ActionProtocolModelTest` (model gerçekten ayrıştırılabilir bir eylem etiketi üretiyor mu) iki gerçek ağırlıkla ayrıca koşuldu ve **GEÇTİ** (44s) — ancak 260K/15M oyuncak modellerle etiket üretimi, ürün amaçlı modellerde aynı davranışı garanti etmez; büyük modelle tekrarlanması önerilir.

---

## 12. Phase 6 önerisi

1. **`discover`-ile-gizli-olgu politika kararı (öncelikli, küçük)**: `ActionValidator.DISCOVER`'da `fact.secret == true` ise reddet ve modeli `SecretRevealed` benzeri, açıklayıcı denetiminden geçen bilinçli bir yola (veya yalnız "karakterin zaten bildiği olguları pekiştirme" iznine) yönlendir. Böylece ifşa yetkisinin TEK bir otoriter kuralı (`knows`) kalır.
2. **Aynı turdaki çift yoklamayı sadeleştirme** (`respond` içindeki ön-`pollConditionalEvents`), `repeatable=0-bekleme` senaryosu için regresyon testiyle birlikte.
3. **VM kapı akışı için JVM test kancası**: `AppPreferences`'ı arayüz/port yapmak (paralel görev alanına dokunmadan), `openStory`/`retryGeneration`'ın tüm durumlarını VM düzeyinde kilitleyen küçük bir test paketi.
4. **Cihaz doğrulaması kampanyası**: bir Android cihaz/emülatör bağlanınca `RealStoryChatLoopTest` mantığının APK içi karşılığı + dokunmatik sahne akışı + gerçek DURDUR gecikmesi ölçümleri (latans ölçüm çerçevesi paralel görevden teslim alınınca).
5. Kitaplık sonrası enkaz için geliştirici modunda tek satırlık telemetri olayı (3. riskteki sessizliği kırmak).

---

### Kanıt dosyaları
- Test XML'leri: `charaly-runtime/build/test-results/test/`, `app/build/test-results/testDebugUnitTest/`
- APK: `Charaly-debug-2026-10-09-phase5-story-loop.apk` (92.330.157 B)
- Git mutasyonu: **yok** (yalnız `git status`/`git diff` okumaları); paralel görev dosyalarına (`charaly_jni.cpp`, `LocalLlamaInferenceEngine`, `Model*`, `LocalModelResidency`, latans testleri) **dokunulmadı**; çalışma ağacındaki önceki/paralel değişikliklerin tamamı korundu.