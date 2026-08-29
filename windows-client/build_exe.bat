@echo off
echo Installing requirements...
pip install -r requirements.txt

echo.
echo Building Ghost Windows Client EXE...
pyinstaller --noconsole --onefile --name "GhostClient" ghost_client.py

echo.
echo Build complete! Your EXE is located in the "dist" folder.
pause
