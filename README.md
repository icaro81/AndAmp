# AndAmp

Reproductor de audio Hi-Res para Android inspirado en el clásico rack metálico de Winamp, con soporte nativo para **FLAC**, **ALAC**, **DSD (.dsf / .dff)**, ecualizador gráfico de 10 bandas, navegador **Samba / NAS**, búsqueda y reproducción remota de playlists de **YouTube** y actualización interna automática desde GitHub Releases.

## Compilación automática en GitHub Actions

El proyecto incluye el workflow `.github/workflows/android-ci.yml` listo para ejecutarse sin configuración adicional en la pestaña **Actions**:

1. Crea un repositorio nuevo en GitHub (por ejemplo `tu-usuario/AndAmp`).
2. Sube el proyecto a la rama `main`:
   ```bash
   git init
   git add .
   git commit -m "Initial AndAmp release"
   git branch -M main
   git remote add origin https://github.com/tu-usuario/AndAmp.git
   git push -u origin main
   ```
3. Cada `push` ejecuta las pruebas unitarias/Robolectric y genera el archivo `.apk` instalable en los artefactos del workflow.
4. Para publicar una actualización que los usuarios reciban **dentro de la aplicación**, crea una etiqueta con versión semántica (`v1.0.1`, `v1.1.0`, etc.):
   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```
5. Dentro de **AndAmp**, abre **MISC → ACTUALIZACIONES** e ingresa `tu-usuario/AndAmp` para que la app compruebe, anuncie, descargue e instale nuevas versiones directamente sin abrir el navegador.
