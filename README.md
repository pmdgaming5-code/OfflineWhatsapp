# Sinyalce

İnternet olmadan, cihazların Wi‑Fi Direct bağlantısı üzerinden doğrudan mesajlaşması için Android uygulaması.

## Özellikler

- Sinyalce mobil sohbet arayüzü
- Android 13+ hedef SDK 35
- Kalıcı foreground service ile sunucu/istemci bağlantısı
- Uygulama ekranı kapansa da bağlantı devam eder; servis kullanıcı tarafından durdurulana kadar çalışır
- Sunucu adı + oda adı + isteğe bağlı şifre
- Kullanıcı adı / profil adı
- Aynı odada birden fazla cihazla mesajlaşma
- Uygulama ön plandayken yeni mesaj bildirimi yok
- Uygulama arka plandayken yeni mesaj bildirimi
- Mesaj ve görsel ses efektleri
- İnternetsiz görsel gönderme
- Alınan görselleri galeriye kaydetme
- Görseli panoya kopyalama / açma
- Oda bazlı yerel SQLite sohbet geçmişi
- Android 13 `NEARBY_WIFI_DEVICES` ve `POST_NOTIFICATIONS` izinleri
- GitHub Actions ile otomatik debug APK

## Geçmiş sohbetler

Mesajlar cihazın kendi SQLite veritabanında tutulur. İnternet, bulut veya harici sunucuya sohbet geçmişi gönderilmez. Oda adı da kaydedildiği için geçmiş sohbetler odalara göre ayrılır. Uygulama silinirse Android uygulama verileri de silineceğinden yerel geçmiş kaybolabilir.

## Kullanım

1. İki telefona Sinyalce'yi kurun.
2. Açılışta mesajlarda görünecek kendi isminizi belirleyin.
3. Android 13+ cihazlarda Yakındaki Cihazlar ve Bildirim izinlerini verin.
4. Bir telefonda SUNUCU AÇ'a dokunup sunucu adı, oda adı ve isterseniz şifre belirleyin.
5. Diğer telefonda YAKINDAKİLER ile cihazı bulun ve seçin.
6. Aynı oda adını ve gerekiyorsa şifreyi girin.
7. Bağlantı kurulduğunda metin ve görsel gönderebilirsiniz.

## Tamamen offline

Mesaj trafiği internetteki bir sunucuya gitmez. Telefonlar Wi‑Fi Direct P2P grubu ve yerel TCP soketi üzerinden doğrudan haberleşir. `INTERNET` izni Android'in yerel ağ socket API'si için manifestte tutulur; uygulamanın internete ihtiyaç duyduğu anlamına gelmez.

## Menzil

Gerçek menzil telefon donanımına, antene, ortama ve görüş hattına bağlıdır. Uygulama yazılımla belirli bir 1 km menzili garanti edemez.
