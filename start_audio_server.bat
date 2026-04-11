@echo off
echo ========================================
echo  Serveur Audio Timestamping PodMix
echo ========================================
echo.

REM Vérifier si Python est installé
python --version >nul 2>&1
if errorlevel 1 (
    echo ERREUR: Python n'est pas installé ou n'est pas dans le PATH.
    echo Installez Python depuis https://python.org
    pause
    exit /b 1
)

echo Vérification des dépendances Python...
pip install httpx beautifulsoup4 aiohttp fake-useragent --quiet

echo.
echo Démarrage du serveur sur http://localhost:8099
echo Appuyez sur Ctrl+C pour arrêter le serveur
echo.

REM Démarrer le serveur
python audio_timestamp_server.py

if errorlevel 1 (
    echo.
    echo ERREUR: Le serveur n'a pas pu démarrer.
    echo Vérifiez que le port 8099 n'est pas déjà utilisé.
    pause
)