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
3. Cada `push` a `main` ejecuta las pruebas unitarias/Robolectric, sube el artefacto en **Actions → Summary** y publica/actualiza una **Pre-release (`latest-ci`)** en la pestaña **Releases** del repositorio con descarga directa del archivo `.apk` (visible incluso sin iniciar sesión).
4. Para publicar una actualización oficial que los usuarios reciban **dentro de la aplicación**, crea una etiqueta con versión semántica (`v1.0.1`, `v1.1.0`, etc.):
   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```
5. Dentro de **AndAmp**, abre **MISC → ACTUALIZACIONES**: ya viene preconfigurado con `icaro81/AndAmp` para comprobar, anunciar, descargar e instalar nuevas versiones oficiales directamente sin abrir el navegador.
