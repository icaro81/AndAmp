# AndAmp

Reproductor de audio Hi-Res para Android inspirado en el clásico rack metálico de Winamp, con soporte nativo para **FLAC**, **ALAC**, **DSD (.dsf / .dff)**, ecualizador gráfico de 10 bandas, navegador **Samba / NAS**, búsqueda y reproducción remota de playlists de **YouTube** y actualización interna automática desde GitHub Releases.

## Compilación automática en GitHub Actions

El proyecto incluye el workflow `.github/workflows/android-ci.yml` listo para ejecutarse sin configuración adicional en la pestaña **Actions**:

1. Repositorio oficial de GitHub: `icaro81/AndAmp`.
2. Sube cambios a la rama `main`:
   ```bash
   git init
   git add .
   git commit -m "Initial AndAmp release"
   git branch -M main
   git remote add origin https://github.com/icaro81/AndAmp.git
   git push -u origin main
   ```
3. Cada `push` a `main` o ejecución manual (`Run workflow`) ejecuta las pruebas unitarias/Robolectric, adjunta el artefacto en **Actions → Summary** y publica automáticamente una **Release oficial (`Latest`, `v1.0.<run>`)** en la pestaña **Releases** del repositorio (`https://github.com/icaro81/AndAmp/releases/latest`) con descarga directa del archivo `.apk`.
4. Si prefieres usar una versión personalizada (por ejemplo `v1.1.0`), crea y sube una etiqueta:
   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```
5. Dentro de **AndAmp**, abre **MISC → ACTUALIZACIONES** (ya viene preconfigurado con `icaro81/AndAmp`) para comprobar, descargar e instalar cualquier nueva compilación directamente sin abrir el navegador.
