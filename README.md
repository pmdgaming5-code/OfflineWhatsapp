# OfflineWhatsapp

İnternet olmadan iki Android cihaz arasında doğrudan mesajlaşma için Android uygulaması.

## Özellikler

- Android 13+ hedef SDK (API 35)
- Android 8.0+ minimum SDK (API 26)
- Wi‑Fi Direct P2P bağlantısı
- İnternet sunucusu yok
- İki cihaz arasında TCP mesaj kanalı
- Yerel SQLite mesaj geçmişi
- Android 13 `NEARBY_WIFI_DEVICES` izni
- GitHub Actions ile otomatik debug APK

## Kullanım

1. İki telefona uygulamayı kurun.
2. Android 13+ cihazlarda "Yakındaki cihazlar" iznini verin.
3. Bir telefonda `SUNUCU BAŞLAT` seçeneğine dokunun.
4. Diğer telefonda `CİHAZLARI TARA` seçeneğine dokunun.
5. Listeden sunucu cihazını seçin.
6. Bağlantı kurulduğunda mesaj gönderebilirsiniz.

## Menzil

Uygulama bağlantı için Wi‑Fi Direct kullanır. Menzil telefonların Wi‑Fi radyosuna, antenine, çevreye ve görüş hattına bağlıdır. Uygulama yazılımla 1 km menzil garanti edemez.

## Android 13 izinleri

Android 13 ve üstünde Wi‑Fi Direct için `NEARBY_WIFI_DEVICES` runtime izni gerekir. Android 12 ve altı için geriye dönük olarak `ACCESS_FINE_LOCATION` tutulur.

## APK

Her `main` push'unda GitHub Actions `assembleDebug` çalıştırılır ve `OfflineWhatsapp-debug` adlı artifact oluşturulur.
