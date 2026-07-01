# Política de Privacidad — GuacaMalla

**Responsable:** Equipo GuacaMalla  
**Contacto:** carlosrd@gmail.com  
**Fecha de entrada en vigor:** 1 de julio de 2026

---

## 1. ¿Qué es GuacaMalla?

GuacaMalla es una aplicación Android de emergencias que permite emitir y retransmitir señales de auxilio (SOS) cuando la infraestructura de telecomunicaciones no está disponible. Los dispositivos forman una red local entre sí mediante Bluetooth Low Energy (BLE) y Wi-Fi Aware, sin necesidad de servidores ni de conexión a Internet para su función principal.

Cuando el dispositivo recupera conectividad, puede actuar como "mula de datos" y cargar tramas acumuladas a nuestro servidor para ampliar el alcance de la red.

---

## 2. Datos que recopilamos

### 2.1 Datos generados por el uso de la app

| Dato | Descripción | Dónde se almacena |
|---|---|---|
| **Coordenadas GPS** | Latitud y longitud precisas (resolución ~1 cm) incluidas en cada trama SOS o de presencia | Dispositivo local · Mesh BLE · Servidor (Supabase) |
| **Identificador seudónimo del dispositivo** | Clave pública Ed25519 de 32 bytes generada aleatoriamente en el primer inicio; se deriva un identificador de 4 bytes (`node_id`). No vincula al usuario con su identidad real. | Dispositivo local · Mesh BLE · Servidor |
| **Tipo de señal de auxilio** | Uno de ocho valores: `médico`, `auxilio`, `alimentos`, `agua`, `refugio`, `incendio`, `violencia`, `otro` | Dispositivo local · Mesh BLE · Servidor |
| **Nivel de batería** | Aproximación de cuatro niveles (vacío / bajo / medio / lleno) | Dispositivo local · Mesh BLE · Servidor |
| **Marca de tiempo** | Momento de creación de la trama (segundos Unix) | Dispositivo local · Mesh BLE · Servidor |
| **Intensidad de señal BLE (RSSI)** | Potencia de la señal Bluetooth de las tramas recibidas | Solo dispositivo local |
| **Imagen de evidencia** *(opcional)* | Foto de hasta 8 MiB subida únicamente cuando un testigo confirma que una emergencia fue resuelta | Servidor (Supabase Storage) |

### 2.2 Datos de diagnóstico (Firebase Crashlytics)

Cuando la aplicación falla o se congela, Firebase Crashlytics (servicio de Google) recopila automáticamente:

- Modelo y fabricante del dispositivo
- Versión del sistema operativo Android
- Versión de la aplicación
- Trazas de pila del error
- Identificadores internos de instalación de Firebase

Esto ocurre en todas las versiones de la aplicación (incluyendo versiones de prueba).

---

## 3. Datos que **no** recopilamos

- Nombre, apellido, correo electrónico o número de teléfono
- Contraseñas ni credenciales de ningún tipo
- Contactos del dispositivo
- Audio ni imágenes de la cámara (salvo la foto opcional de evidencia de resolución)
- Historial de navegación ni actividad fuera de la app
- Contenido de mensajes de texto o llamadas

---

## 4. Para qué usamos los datos

| Finalidad | Datos utilizados |
|---|---|
| **Emitir y retransmitir señales SOS** | Coordenadas GPS, tipo de auxilio, identificador seudónimo, marca de tiempo, nivel de batería |
| **Mostrar el radar de proximidad** | Coordenadas GPS propias y de tramas recibidas por BLE |
| **Cargar tramas al servidor** (función mula de datos) | Todas las tramas recopiladas — verificadas criptográficamente antes de persistirse |
| **Construir el mapa de ubicaciones en el servidor** | Coordenadas GPS extraídas de tramas verificadas; `deviceId` derivado de la clave pública |
| **Confirmar resolución de emergencias** | Coordenadas GPS, identificador y firma del testigo, imagen opcional |
| **Mejorar la estabilidad de la app** | Informes de fallos enviados a Firebase Crashlytics |

---

## 5. Con quién compartimos los datos

No vendemos ni cedemos datos personales a terceros con fines comerciales o publicitarios. Los datos se comparten únicamente con los siguientes proveedores de infraestructura:

### Google / Firebase Crashlytics
- **Qué recibe:** informes de fallos (modelo, OS, versión de app, traza de pila)
- **Por qué:** detectar y corregir errores de la aplicación
- **Política de privacidad:** [firebase.google.com/support/privacy](https://firebase.google.com/support/privacy)

### Google Play Services (FusedLocationProvider)
- **Qué recibe:** solicitudes de actualización de ubicación GPS
- **Por qué:** obtener la posición del dispositivo para incluirla en las tramas SOS
- **Política de privacidad:** [policies.google.com/privacy](https://policies.google.com/privacy)

### Supabase
- **Qué recibe:** tramas SOS verificadas, historial de coordenadas, imágenes de evidencia
- **Por qué:** almacenamiento en la nube del servidor GuacaMalla
- **Política de privacidad:** [supabase.com/privacy](https://supabase.com/privacy)
- **Nota:** Supabase aloja bases de datos en servidores de AWS. Los datos pueden residir fuera de tu país de residencia.

---

## 6. Almacenamiento y seguridad

### En el dispositivo

- La base de datos local (SQLite, `guacamaya.db`) guarda hasta **25 000 tramas**. Las más antiguas se eliminan automáticamente al superar ese límite.
- La clave privada Ed25519 se cifra con **AES-256-GCM** usando una clave maestra almacenada en el **Android Keystore** — nunca sale del dispositivo en texto claro.
- La copia de seguridad automática de Android (`allowBackup`) está desactivada: los datos de la app no se sincronizan con Google Drive.

### En tránsito

- Toda comunicación con el servidor usa **HTTPS** (TLS).
- Las tramas BLE son verificadas criptográficamente (firma Ed25519) antes de persistirse en el servidor; el servidor nunca confía en datos sin verificar.

### En el servidor

- Los datos en Supabase están protegidos con **Row Level Security (RLS)**.
- Solo el servicio interno con clave de rol de servicio puede leer o escribir registros.

---

## 7. Retención de datos

| Datos | Retención |
|---|---|
| Tramas en el dispositivo | Hasta 25 000 entradas; las más antiguas se eliminan automáticamente |
| Registros SOS en el servidor | Actualmente se conservan indefinidamente — planeamos añadir eliminación automática en una actualización futura |
| Historial de coordenadas en el servidor | Actualmente se conserva indefinidamente — planeamos añadir eliminación automática en una actualización futura |
| Imágenes de evidencia | Conservadas mientras el registro de resolución asociado exista |
| Informes de Firebase Crashlytics | Según la política de retención de Firebase (normalmente 90 días) |

> **Nota importante sobre la propagación mesh:** cuando tu dispositivo emite una trama SOS, ésta puede ser retransmitida por otros dispositivos cercanos de forma automática. Una vez propagada por la malla, el Equipo GuacaMalla no puede eliminar esas copias de dispositivos de terceros. Esto es inherente al diseño de la red y tiene como único fin que tu señal de auxilio llegue a más personas.

---

## 8. Tus derechos

Aunque la app no crea cuentas de usuario, tienes los siguientes derechos respecto a los datos almacenados en nuestro servidor:

- **Derecho a saber:** puedes preguntarnos qué datos asociados a tu clave pública tenemos almacenados.
- **Derecho a eliminar:** puedes solicitar la eliminación de los registros del servidor asociados a tu identificador seudónimo (clave pública). Para ello escríbenos a **carlosrd@gmail.com** indicando tu clave pública (se muestra en la pantalla "Acerca de" de la app).
- **Limitación:** los datos ya propagados por la malla BLE de otros usuarios no pueden ser recuperados ni eliminados por nosotros.

Para desinstalar la app y eliminar todos los datos locales, basta con desinstalarla desde el sistema operativo.

---

## 9. Menores de edad

GuacaMalla no está dirigida a personas menores de 13 años. No recopilamos intencionalmente datos de menores de esa edad. Si crees que un menor nos ha proporcionado datos sin el consentimiento de un tutor, contáctanos en **carlosrd@gmail.com** para eliminarlos.

---

## 10. Cambios a esta política

Podemos actualizar esta política en cualquier momento. Cuando lo hagamos, actualizaremos la **fecha de entrada en vigor** al inicio de este documento. Te recomendamos revisarla periódicamente. El uso continuado de la app tras la publicación de cambios implica la aceptación de la versión vigente.

---

## 11. Contacto

Si tienes preguntas, solicitudes de eliminación de datos o cualquier inquietud sobre privacidad, escríbenos a:

**Equipo GuacaMalla**  
📧 carlosrd@gmail.com

Respondemos en un plazo de 30 días hábiles.
