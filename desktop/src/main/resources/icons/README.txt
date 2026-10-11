FocusDuo application icon

Source: focusduo.svg, copied from the backend team's FocusDuo README mark.
It retains the existing Compose FocusDuoLogo geometry and colors: two
circles (37,36)/(51,36), radius 27, #B0C7A7 and #1C543D at 0.86 opacity.

Rebuild from the repository root with Python 3 (no additional packages):
    python desktop/scripts/generate-app-icon.py

The renderer centers the SVG artwork on a transparent square and uses
8x supersampling. focusduo.png is a 256x256 RGBA Compose Window icon.
focusduo.ico contains PNG frames at 16, 24, 32, 48, 64, 128 and 256 pixels
for the Windows 10/11 executable, MSI, desktop and Start menu shortcuts.

No project license is assigned by adding this resource.
