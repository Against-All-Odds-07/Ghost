import customtkinter as ctk
import asyncio
import websockets
import json
import base64
from io import BytesIO
from PIL import Image
import threading
import uuid
import time
import webbrowser

ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

class GhostClientApp(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title("Ghost Remote Commander - Ultra Fast Screen Stream")
        self.geometry("1200x780")
        self.configure(fg_color="#090A0F")
        
        self.ws = None
        self.connected = False
        self.token = None
        self.last_location_data = None
        self.pending_responses = {}
        
        # FPS Tracking & Metrics
        self.frame_count = 0
        self.fps = 0.0
        self.last_fps_time = time.time()
        self.last_frame_rtt = 0
        self.stream_active = True
        self.stream_quality = 55
        self.stream_scale = 0.6
        
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.start_async_loop, daemon=True).start()
        
        self.build_ui()
        
    def start_async_loop(self):
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def build_ui(self):
        # Sidebar
        self.sidebar = ctk.CTkFrame(self, width=230, corner_radius=0, fg_color="#12141F")
        self.sidebar.pack(side="left", fill="y")
        
        self.logo = ctk.CTkLabel(self.sidebar, text="👻 GHOST", font=ctk.CTkFont(size=22, weight="bold"), text_color="#A78BFA")
        self.logo.pack(pady=(24, 16), padx=20)

        self.conn_badge = ctk.CTkLabel(self.sidebar, text="● Disconnected", font=ctk.CTkFont(size=12), text_color="#EF4444")
        self.conn_badge.pack(pady=(0, 20), padx=20)
        
        self.btn_remote = ctk.CTkButton(
            self.sidebar, text="📱 Live Remote (60 FPS)", anchor="w",
            fg_color="#1E2235", text_color="#FFFFFF", hover_color="#2D324D",
            height=40, font=ctk.CTkFont(size=13, weight="bold"),
            command=lambda: self.show_frame("remote")
        )
        self.btn_remote.pack(pady=6, padx=16, fill="x")

        self.btn_location = ctk.CTkButton(
            self.sidebar, text="📍 GPS Location", anchor="w",
            fg_color="transparent", text_color="#C4C9DF", hover_color="#1E2235",
            height=40, font=ctk.CTkFont(size=13),
            command=lambda: self.show_frame("location")
        )
        self.btn_location.pack(pady=6, padx=16, fill="x")

        self.btn_connect = ctk.CTkButton(
            self.sidebar, text="⚙️ Connection Setup", anchor="w",
            fg_color="transparent", text_color="#C4C9DF", hover_color="#1E2235",
            height=40, font=ctk.CTkFont(size=13),
            command=lambda: self.show_frame("connect")
        )
        self.btn_connect.pack(pady=6, padx=16, fill="x")

        # Sidebar footer info
        spacer = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        spacer.pack(expand=True, fill="both")
        
        self.side_fps_lbl = ctk.CTkLabel(self.sidebar, text="Stream: Idle", font=ctk.CTkFont(size=11), text_color="#8E96B4")
        self.side_fps_lbl.pack(side="bottom", pady=16)
        
        # Main container
        self.main_container = ctk.CTkFrame(self, fg_color="#090A0F")
        self.main_container.pack(side="right", fill="both", expand=True)
        
        self.frames = {}
        
        # 1. Connection Frame
        self.frames["connect"] = ctk.CTkFrame(self.main_container, fg_color="transparent")
        ctk.CTkLabel(
            self.frames["connect"],
            text="Connect to Ghost Android",
            font=ctk.CTkFont(size=24, weight="bold"),
            text_color="#FFFFFF"
        ).pack(pady=(40, 8))

        ctk.CTkLabel(
            self.frames["connect"],
            text="Enter device Tailscale IP or LAN IP and 6-Digit Pairing Code",
            font=ctk.CTkFont(size=13),
            text_color="#8E96B4"
        ).pack(pady=(0, 24))

        self.ip_entry = ctk.CTkEntry(
            self.frames["connect"],
            placeholder_text="Device IP (e.g. 100.x.x.x or 192.168.x.x)",
            width=360, height=44, font=ctk.CTkFont(size=14)
        )
        self.ip_entry.pack(pady=8)

        self.code_entry = ctk.CTkEntry(
            self.frames["connect"],
            placeholder_text="6-Digit Pairing Code",
            width=360, height=44, font=ctk.CTkFont(size=14)
        )
        self.code_entry.pack(pady=8)

        self.connect_btn = ctk.CTkButton(
            self.frames["connect"],
            text="Pair & Connect",
            fg_color="#7C3AED", hover_color="#6D28D9",
            height=44, width=360, font=ctk.CTkFont(size=14, weight="bold"),
            command=self.on_connect_click
        )
        self.connect_btn.pack(pady=16)

        self.status_lbl = ctk.CTkLabel(self.frames["connect"], text="Ready to connect", text_color="#8E96B4")
        self.status_lbl.pack(pady=8)
        
        # 2. Remote Screen Frame
        self.frames["remote"] = ctk.CTkFrame(self.main_container, fg_color="transparent")
        
        # Stream Top Bar
        remote_header = ctk.CTkFrame(self.frames["remote"], height=44, fg_color="#12141F", corner_radius=12)
        remote_header.pack(fill="x", padx=16, pady=(12, 6))
        
        self.stream_status = ctk.CTkLabel(
            remote_header,
            text="● Live Screen Stream (Hardware / Adaptive)",
            font=ctk.CTkFont(size=12, weight="bold"),
            text_color="#10B981"
        )
        self.stream_status.pack(side="left", padx=16)

        # Quality Preset Dropdown
        self.quality_menu = ctk.CTkOptionMenu(
            remote_header,
            values=["Ultra Fast (60 FPS)", "Balanced (30 FPS)", "High Quality (720p)"],
            command=self.on_quality_change,
            width=170, height=30, fg_color="#1E2235", button_color="#2D324D"
        )
        self.quality_menu.pack(side="right", padx=12)

        self.fps_lbl = ctk.CTkLabel(
            remote_header,
            text="FPS: -- | Latency: --ms",
            font=ctk.CTkFont(size=12, weight="bold"),
            text_color="#A78BFA"
        )
        self.fps_lbl.pack(side="right", padx=16)

        # Screen View Canvas Container
        self.screen_container = ctk.CTkFrame(self.frames["remote"], fg_color="#000000", corner_radius=14)
        self.screen_container.pack(pady=6, padx=16, expand=True, fill="both")

        self.screen_lbl = ctk.CTkLabel(self.screen_container, text="Awaiting active video feed...")
        self.screen_lbl.pack(expand=True, fill="both")
        
        self.screen_lbl.bind("<Button-1>", self.on_screen_click)
        self.screen_lbl.bind("<B1-Motion>", self.on_screen_drag)
        self.screen_lbl.bind("<ButtonRelease-1>", self.on_screen_release)
        
        # Text Typing & Send Bar
        type_bar = ctk.CTkFrame(self.frames["remote"], height=40, fg_color="#12141F", corner_radius=10)
        type_bar.pack(fill="x", padx=16, pady=(4, 6))
        
        self.type_entry = ctk.CTkEntry(
            type_bar,
            placeholder_text="Type text here to paste directly into phone focused input field...",
            height=32, font=ctk.CTkFont(size=12)
        )
        self.type_entry.pack(side="left", fill="x", expand=True, padx=(10, 8), pady=4)
        self.type_entry.bind("<Return>", lambda e: self.send_typed_text())

        ctk.CTkButton(
            type_bar, text="Send Text ↵", width=95, height=30,
            fg_color="#7C3AED", hover_color="#6D28D9",
            command=self.send_typed_text
        ).pack(side="right", padx=(0, 10), pady=4)

        # Android Controls Navigation Bar
        controls = ctk.CTkFrame(self.frames["remote"], height=52, fg_color="#12141F", corner_radius=14)
        controls.pack(fill="x", pady=(2, 12), padx=16)
        
        ctk.CTkButton(controls, text="◀ BACK", width=75, height=34, fg_color="#1E2235", hover_color="#2D324D", font=ctk.CTkFont(weight="bold"), command=lambda: self.send_key("BACK")).pack(side="left", padx=6, pady=8)
        ctk.CTkButton(controls, text="⌂ HOME", width=75, height=34, fg_color="#1E2235", hover_color="#2D324D", font=ctk.CTkFont(weight="bold"), command=lambda: self.send_key("HOME")).pack(side="left", padx=6, pady=8)
        ctk.CTkButton(controls, text="▢ RECENTS", width=85, height=34, fg_color="#1E2235", hover_color="#2D324D", font=ctk.CTkFont(weight="bold"), command=lambda: self.send_key("APP_SWITCH")).pack(side="left", padx=6, pady=8)
        ctk.CTkButton(controls, text="⏻ LOCK", width=75, height=34, fg_color="#1E2235", hover_color="#2D324D", font=ctk.CTkFont(weight="bold"), command=lambda: self.send_key("POWER")).pack(side="left", padx=6, pady=8)
        
        ctk.CTkButton(controls, text="VOL -", width=60, height=34, fg_color="#1E2235", hover_color="#2D324D", command=lambda: self.send_volume("DOWN")).pack(side="right", padx=5, pady=8)
        ctk.CTkButton(controls, text="VOL +", width=60, height=34, fg_color="#1E2235", hover_color="#2D324D", command=lambda: self.send_volume("UP")).pack(side="right", padx=5, pady=8)
        
        # 3. Location Frame
        self.frames["location"] = ctk.CTkFrame(self.main_container, fg_color="transparent")
        
        loc_title_box = ctk.CTkFrame(self.frames["location"], fg_color="transparent")
        loc_title_box.pack(fill="x", padx=24, pady=(24, 12))
        
        ctk.CTkLabel(
            loc_title_box,
            text="Device Geolocation & Tracking",
            font=ctk.CTkFont(size=22, weight="bold"),
            text_color="#FFFFFF"
        ).pack(side="left")

        self.btn_refresh_loc = ctk.CTkButton(
            loc_title_box,
            text="🔄 Find Location Now",
            fg_color="#7C3AED", hover_color="#6D28D9",
            height=36, font=ctk.CTkFont(size=13, weight="bold"),
            command=self.fetch_location
        )
        self.btn_refresh_loc.pack(side="right")

        # Location details card
        self.loc_card = ctk.CTkFrame(self.frames["location"], fg_color="#12141F", corner_radius=18)
        self.loc_card.pack(fill="both", expand=True, padx=24, pady=(0, 24))

        self.loc_status_lbl = ctk.CTkLabel(
            self.loc_card,
            text="Click 'Find Location Now' to query high-precision GPS coordinates.",
            font=ctk.CTkFont(size=14),
            text_color="#8E96B4"
        )
        self.loc_status_lbl.pack(pady=20)

        # Coordinates info grid
        self.coords_frame = ctk.CTkFrame(self.loc_card, fg_color="#1A1D2D", corner_radius=14)
        self.coords_frame.pack(fill="x", padx=24, pady=12)

        self.lat_lbl = ctk.CTkLabel(self.coords_frame, text="Latitude: --", font=ctk.CTkFont(size=15, weight="bold"), text_color="#FFFFFF")
        self.lat_lbl.pack(anchor="w", padx=20, pady=(14, 4))

        self.lng_lbl = ctk.CTkLabel(self.coords_frame, text="Longitude: --", font=ctk.CTkFont(size=15, weight="bold"), text_color="#FFFFFF")
        self.lng_lbl.pack(anchor="w", padx=20, pady=4)

        self.acc_lbl = ctk.CTkLabel(self.coords_frame, text="Accuracy: --", font=ctk.CTkFont(size=13), text_color="#10B981")
        self.acc_lbl.pack(anchor="w", padx=20, pady=(4, 14))

        # Address Box
        self.addr_frame = ctk.CTkFrame(self.loc_card, fg_color="#1A1D2D", corner_radius=14)
        self.addr_frame.pack(fill="x", padx=24, pady=12)

        ctk.CTkLabel(self.addr_frame, text="PHYSICAL ADDRESS", font=ctk.CTkFont(size=11, weight="bold"), text_color="#8E96B4").pack(anchor="w", padx=20, pady=(12, 4))
        self.addr_lbl = ctk.CTkLabel(
            self.addr_frame,
            text="No location fetched yet.",
            font=ctk.CTkFont(size=14),
            text_color="#FFFFFF",
            wraplength=700,
            justify="left"
        )
        self.addr_lbl.pack(anchor="w", padx=20, pady=(0, 14))

        # Action button to open Google Maps
        self.btn_maps = ctk.CTkButton(
            self.loc_card,
            text="🌐 Open Coordinates in Google Maps (Browser)",
            fg_color="#2563EB", hover_color="#1D4ED8",
            height=42, font=ctk.CTkFont(size=14, weight="bold"),
            command=self.open_google_maps
        )
        self.btn_maps.pack(padx=24, pady=16, fill="x")

        self.drag_start = None
        self.show_frame("connect")
        
    def show_frame(self, name):
        for k, frame in self.frames.items():
            frame.pack_forget()
        self.frames[name].pack(fill="both", expand=True)

        self.btn_remote.configure(fg_color="#1E2235" if name == "remote" else "transparent")
        self.btn_location.configure(fg_color="#1E2235" if name == "location" else "transparent")
        self.btn_connect.configure(fg_color="#1E2235" if name == "connect" else "transparent")

        if name == "location" and self.connected and not self.last_location_data:
            self.fetch_location()

    def on_quality_change(self, choice):
        if "Ultra Fast" in choice:
            self.stream_quality = 45
            self.stream_scale = 0.5
        elif "Balanced" in choice:
            self.stream_quality = 60
            self.stream_scale = 0.7
        else: # High Quality
            self.stream_quality = 75
            self.stream_scale = 1.0
        
    def on_connect_click(self):
        ip = self.ip_entry.get().strip()
        code = self.code_entry.get().strip()
        if not ip: return
        self.status_lbl.configure(text="Connecting...", text_color="#8E96B4")
        asyncio.run_coroutine_threadsafe(self.connect_ws(ip, code), self.loop)
        
    async def connect_ws(self, ip, code):
        uri = f"ws://{ip}:8765"
        try:
            self.ws = await websockets.connect(uri, max_size=10_000_000, ping_interval=10, ping_timeout=10)
            self.connected = True
            
            # Authenticate
            auth_req = {
                "id": str(uuid.uuid4()),
                "type": "session.authenticate",
                "payload": {"clientId": "WINDOWS-PC", "pairingCode": code}
            }
            await self.ws.send(json.dumps(auth_req))
            
            resp = json.loads(await self.ws.recv())
            if resp.get("ok"):
                self.token = resp["payload"].get("token")
                self.status_lbl.configure(text="Connected & Authenticated!", text_color="#10B981")
                self.conn_badge.configure(text="● Connected", text_color="#10B981")
                self.after(500, lambda: self.show_frame("remote"))
                
                # Start message listener and ultra-smooth frame pipeline
                asyncio.create_task(self.listen_messages())
                asyncio.create_task(self.ultra_screen_stream_loop())
            else:
                self.status_lbl.configure(text=f"Error: {resp.get('error')}", text_color="#EF4444")
                
        except Exception as e:
            self.status_lbl.configure(text=f"Connection Failed: {e}", text_color="#EF4444")
            
    async def listen_messages(self):
        try:
            async for message in self.ws:
                data = json.loads(message)
                req_id = data.get("id")
                
                if req_id and req_id in self.pending_responses:
                    future = self.pending_responses.pop(req_id)
                    if not future.done():
                        future.set_result(data)

                if data.get("ok") and data.get("payload") and "image" in data["payload"]:
                    mode = data["payload"].get("fpsMode", "hardware")
                    self.update_screen(data["payload"]["image"], mode)
        except Exception as e:
            self.connected = False
            self.conn_badge.configure(text="● Disconnected", text_color="#EF4444")
            self.status_lbl.configure(text=f"Disconnected: {e}", text_color="#EF4444")
            self.show_frame("connect")

    async def ultra_screen_stream_loop(self):
        """High-frequency pipelined capture loop for 30-60 FPS live video streaming."""
        while self.connected and self.stream_active:
            t0 = time.time()
            req_id = str(uuid.uuid4())
            future = self.loop.create_future()
            self.pending_responses[req_id] = future
            
            req = {
                "id": req_id,
                "type": "screen.capture",
                "payload": {"quality": self.stream_quality, "scale": self.stream_scale}
            }
            try:
                await self.ws.send(json.dumps(req))
                # Wait for frame with short timeout so it never blocks
                resp = await asyncio.wait_for(future, timeout=0.8)
                self.last_frame_rtt = int((time.time() - t0) * 1000)
            except Exception:
                self.pending_responses.pop(req_id, None)
                await asyncio.sleep(0.05)
                continue

            # Frame pacing: minimal sleep for ~30-60 FPS continuous streaming
            await asyncio.sleep(0.015)

    def update_screen(self, base64_str, mode="hardware"):
        try:
            if base64_str.startswith("data:image"):
                base64_str = base64_str.split(",")[1]
                
            img_data = base64.b64decode(base64_str)
            img = Image.open(BytesIO(img_data))
            
            lbl_w = max(self.screen_container.winfo_width() - 20, 200)
            lbl_h = max(self.screen_container.winfo_height() - 20, 300)
            
            img.thumbnail((lbl_w, lbl_h), Image.Resampling.BILINEAR)
            self.current_img_size = img.size
            
            ctk_img = ctk.CTkImage(light_image=img, dark_image=img, size=img.size)
            self.screen_lbl.configure(image=ctk_img, text="")
            
            # FPS Calculation
            self.frame_count += 1
            now = time.time()
            elapsed = now - self.last_fps_time
            if elapsed >= 1.0:
                self.fps = self.frame_count / elapsed
                self.frame_count = 0
                self.last_fps_time = now
                
                status_txt = "● 60 FPS Hardware Mirroring" if "hardware" in mode else "● Accessibility Streaming"
                self.stream_status.configure(
                    text=status_txt,
                    text_color="#10B981" if "hardware" in mode else "#F59E0B"
                )
                self.fps_lbl.configure(text=f"FPS: {self.fps:.1f} | Latency: {self.last_frame_rtt}ms")
                self.side_fps_lbl.configure(text=f"Live: {self.fps:.1f} FPS ({self.last_frame_rtt}ms)")
        except Exception:
            pass

    def on_screen_click(self, event):
        self.drag_start = (event.x, event.y)
        
    def on_screen_drag(self, event):
        pass

    def on_screen_release(self, event):
        if not hasattr(self, 'current_img_size') or not self.drag_start:
            return
            
        lbl_w = self.screen_lbl.winfo_width()
        lbl_h = self.screen_lbl.winfo_height()
        img_w, img_h = self.current_img_size
        
        offset_x = (lbl_w - img_w) // 2
        offset_y = (lbl_h - img_h) // 2
        
        start_x = self.drag_start[0] - offset_x
        start_y = self.drag_start[1] - offset_y
        end_x = event.x - offset_x
        end_y = event.y - offset_y
        
        dist_sq = (end_x - start_x)**2 + (end_y - start_y)**2
        
        if dist_sq < 49: # Tap / Click
            if 0 <= start_x <= img_w and 0 <= start_y <= img_h:
                req = {
                    "id": str(uuid.uuid4()),
                    "type": "input.tap",
                    "payload": {"x": start_x / img_w, "y": start_y / img_h}
                }
                if self.ws:
                    asyncio.run_coroutine_threadsafe(self.ws.send(json.dumps(req)), self.loop)
        else: # Swipe / Drag Gesture
            req = {
                "id": str(uuid.uuid4()),
                "type": "input.swipe",
                "payload": {
                    "startX": max(0.0, min(1.0, start_x / img_w)),
                    "startY": max(0.0, min(1.0, start_y / img_h)),
                    "endX": max(0.0, min(1.0, end_x / img_w)),
                    "endY": max(0.0, min(1.0, end_y / img_h)),
                    "duration": 200
                }
            }
            if self.ws:
                asyncio.run_coroutine_threadsafe(self.ws.send(json.dumps(req)), self.loop)

        self.drag_start = None

    def send_typed_text(self):
        txt = self.type_entry.get()
        if not txt or not self.ws: return
        req = {
            "id": str(uuid.uuid4()),
            "type": "clipboard.set",
            "payload": {"text": txt}
        }
        asyncio.run_coroutine_threadsafe(self.ws.send(json.dumps(req)), self.loop)
        self.type_entry.delete(0, "end")

    def send_key(self, keycode):
        req = {
            "id": str(uuid.uuid4()),
            "type": "input.key",
            "payload": {"keyCode": keycode}
        }
        if self.ws:
            asyncio.run_coroutine_threadsafe(self.ws.send(json.dumps(req)), self.loop)

    def send_volume(self, direction):
        req = {
            "id": str(uuid.uuid4()),
            "type": "volume.adjust",
            "payload": {"direction": direction}
        }
        if self.ws:
            asyncio.run_coroutine_threadsafe(self.ws.send(json.dumps(req)), self.loop)

    def fetch_location(self):
        if not self.connected:
            self.loc_status_lbl.configure(text="Device is disconnected. Connect first.", text_color="#EF4444")
            return
            
        self.loc_status_lbl.configure(text="Acquiring GPS fix & geocoding address...", text_color="#A78BFA")
        self.btn_refresh_loc.configure(state="disabled")
        
        asyncio.run_coroutine_threadsafe(self.request_location_coro(), self.loop)

    async def request_location_coro(self):
        req_id = str(uuid.uuid4())
        future = self.loop.create_future()
        self.pending_responses[req_id] = future
        
        req = {
            "id": req_id,
            "type": "device.location",
            "payload": {"fresh": True, "geocode": True}
        }
        try:
            await self.ws.send(json.dumps(req))
            resp = await asyncio.wait_for(future, timeout=8.0)
            
            if resp.get("ok"):
                payload = resp.get("payload", {})
                self.last_location_data = payload
                self.after(0, lambda: self.display_location(payload))
            else:
                err = resp.get("error", "Unknown error")
                self.after(0, lambda: self.loc_status_lbl.configure(text=f"Location Error: {err}", text_color="#EF4444"))
        except Exception as e:
            self.after(0, lambda: self.loc_status_lbl.configure(text=f"Failed to fetch location: {e}", text_color="#EF4444"))
        finally:
            self.pending_responses.pop(req_id, None)
            self.after(0, lambda: self.btn_refresh_loc.configure(state="normal"))

    def display_location(self, data):
        lat = data.get("latitude")
        lng = data.get("longitude")
        acc = data.get("accuracy", 0.0)
        provider = data.get("provider", "GPS")
        
        self.lat_lbl.configure(text=f"Latitude: {lat:.6f}°")
        self.lng_lbl.configure(text=f"Longitude: {lng:.6f}°")
        self.acc_lbl.configure(text=f"Accuracy: ±{acc:.1f}m • Provider: {provider.upper()}")
        
        addr_info = data.get("address", {})
        if addr_info and addr_info.get("fullAddress"):
            self.addr_lbl.configure(text=addr_info.get("fullAddress"))
        else:
            self.addr_lbl.configure(text=f"Coordinates: {lat}, {lng} (Reverse geocoding unavailable)")
            
        self.loc_status_lbl.configure(text="Location acquired successfully.", text_color="#10B981")

    def open_google_maps(self):
        if self.last_location_data and self.last_location_data.get("mapsUrl"):
            webbrowser.open(self.last_location_data["mapsUrl"])
        elif self.last_location_data and "latitude" in self.last_location_data:
            url = f"https://www.google.com/maps/search/?api=1&query={self.last_location_data['latitude']},{self.last_location_data['longitude']}"
            webbrowser.open(url)
        else:
            self.loc_status_lbl.configure(text="No location data to open in Google Maps.", text_color="#F59E0B")

if __name__ == "__main__":
    app = GhostClientApp()
    app.mainloop()
