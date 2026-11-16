# Aula en Calma

Aplicación nativa para Android que mide la **intensidad relativa** del sonido de una clase y reproduce un aviso cuando el ruido se mantiene por encima del umbral elegido.

**No se graban ni se transmiten conversaciones.** Solo se calcula el nivel RMS de bloques de audio en memoria. No requiere Internet ni contiene anuncios.

## Crear un APK sin Android Studio

1. Descomprime `AulaEnCalma_GitHub.zip` en tu ordenador.
2. Abre [github.com/new](https://github.com/new) y crea un repositorio **privado** llamado, por ejemplo, `AulaEnCalma`. Es importante no añadir un README inicial, para mantener la subida sencilla.
3. Dentro del repositorio, pulsa **Add file → Upload files**. Arrastra **todo el contenido** de la carpeta `AulaEnCalma` descomprimida, incluyendo la carpeta **`.github`** (es imprescindible para la automatización). No subas solo el ZIP. Confirma con **Commit changes**.
4. Abre la pestaña **Actions**, elige **Construir APK** y, si la ejecución no se ha iniciado sola con la subida, pulsa **Run workflow**.
5. Cuando termine en verde, abre la ejecución y, abajo, descarga el artefacto **AulaEnCalma-APK**.
6. Extrae ese artefacto: dentro está **`app-debug.apk`**, el instalador. Pásalo al móvil e instálalo. Android puede pedir permiso para instalar aplicaciones de esa fuente.

**Importante:** GitHub entrega un archivo ZIP con el APK dentro. No intentes instalar el ZIP como si fuese un APK.

Si GitHub te muestra el botón «Enable workflows», actívalo. El flujo automático aparece en `.github/workflows/construir-apk.yml`.

## Uso diario

1. Abre **Aula en Calma** y pulsa **Iniciar vigilancia**.
2. Permite el micrófono y, si aparece, las notificaciones.
3. Ajusta el umbral o pulsa **Calibrar aula durante 8 s**, con el alumnado al nivel de ruido que consideras adecuado. Necesita que la vigilancia esté iniciada.
4. Ajusta el ataque (2 segundos de partida), el release (3 segundos), la pausa (15 segundos) y la repetición (30 segundos).
5. Elige un aviso MP3/WAV/OGG mediante el selector de archivos de Android, o usa el pitido integrado.
6. Bloquea la pantalla. La aplicación sigue funcionando mediante un servicio de micrófono en primer plano; verás una notificación desde la que también puedes detenerla.

El volumen dentro de la aplicación se multiplica por el volumen multimedia del dispositivo. Verifica que este último sea audible y comprueba el efecto del modo No molestar antes de dar clase. El detector se pausa mientras reproduce un aviso y durante 1,5 segundos después, para evitar que el móvil se responda a sí mismo.

## Qué significa la escala 0–100

Es un **índice relativo del micrófono**, no decibelios SPL calibrados. Internamente se usa dBFS (nivel de la señal digital) entre -75 y -15 dBFS para representar 0–100. Cada modelo de teléfono tiene diferente sensibilidad y control automático de ganancia. Para un uso comparable, mantén el teléfono en el mismo sitio y utiliza la calibración en esa aula.

El detector mantiene el ruido elevado durante el **ataque** para activar un aviso. Tras una alerta, pide un nivel 5 dB inferior al umbral durante el **release** para volver a armarse (histéresis). Si está activada la repetición, vuelve a avisar mientras el ruido sigue alto, respetando el intervalo.

## Requisitos y limitaciones

- Android 8.0 o posterior (API 26+), micrófono disponible.
- Debes **iniciar** la vigilancia con la app visible y el permiso de micrófono concedido. Android no permite reiniciar de forma arbitraria un servicio de micrófono desde segundo plano.
- Se muestra una notificación durante la vigilancia. La app utiliza un *partial wake lock* mientras el micrófono está activo, lo que puede consumir batería.
- Algunos fabricantes interrumpen servicios al aplicar ahorro extremo de batería. Desactiva la restricción de batería para esta aplicación si el sistema la detiene al bloquear el móvil.
- Si el usuario fuerza el cierre de la aplicación, revoca el micrófono o reinicia el teléfono, tendrá que volver a iniciar la vigilancia manualmente.
- Los archivos de audio se referencian mediante el selector seguro de documentos de Android. Si eliminas el archivo o el permiso de lectura, sonará el pitido de reserva.
- No garantiza la captación de micrófono si otra aplicación o una llamada lo ocupa.

## Por qué se compila en GitHub

GitHub Actions ejecuta Gradle 8.11.1, Android Gradle Plugin 8.9.2, Java 17 y SDK Android 35. El flujo ejecuta las pruebas unitarias de la lógica y genera `app/build/outputs/apk/debug/app-debug.apk`. **No hace falta Android Studio en el ordenador.**

Este APK usa la firma de **depuración de GitHub**, que puede cambiar en compilaciones posteriores. Para actualizar una instalación con un APK generado en una ejecución distinta puede ser necesario desinstalar el anterior primero (se perderán sus ajustes). No publiques este APK como versión de Play Store. Si algún día quieres actualizaciones firmadas de manera persistente, configura una firma de publicación segura mediante GitHub Secrets, sin subir claves privadas al repositorio.

## Archivos principales

- `app/src/main/java/es/aulaencalma/MainActivity.kt` — controles, perfiles, permisos y selección de archivos.
- `app/src/main/java/es/aulaencalma/NoiseMonitorService.kt` — grabación temporal en RAM, medición, notificación y servicio de micrófono.
- `app/src/main/java/es/aulaencalma/NoiseLogic.kt` — motor de umbrales, ataque, release, histéresis y repetición, aislado de Android y comprobable con pruebas.
- `app/src/main/java/es/aulaencalma/AlarmPlayer.kt` — reproducción de sonido y pitido de reserva.
- `app/src/test/java/es/aulaencalma/NoiseLogicTest.kt` — pruebas automáticas.
- `.github/workflows/construir-apk.yml` — generación del APK.

## Datos y privacidad

La aplicación no solicita permiso de Internet, no contacta con servidores ni guarda archivos de micrófono. Únicamente guarda localmente opciones de configuración y la URI del archivo de aviso seleccionado. Aunque el sonido se analiza sin grabarse, comprueba las normas internas de tu centro para el uso de micrófonos en el aula.
