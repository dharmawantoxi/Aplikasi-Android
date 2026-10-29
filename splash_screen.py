import tkinter as tk
import os
import sys

try:
    from config import resource_path
except ImportError:                     # config.py tidak ikut ter-bundle
    def resource_path(relative_path):
        """Cari file baik saat script maupun .exe"""
        try:
            base_path = sys._MEIPASS
        except AttributeError:
            base_path = os.path.abspath(".")
        return os.path.join(base_path, relative_path)


class SplashScreen:
    def __init__(self, duration=3000):
        """
        duration: berapa lama splash tampil (ms)
                  3000 = 3 detik
        """
        self.duration = duration
        self.splash = tk.Tk()
        self.splash.overrideredirect(True)
        self.splash.configure(bg='#000000')

        # Ukuran splash
        width = 500
        height = 580

        # Tengahkan di layar
        sw = self.splash.winfo_screenwidth()
        sh = self.splash.winfo_screenheight()
        x = (sw - width) // 2
        y = (sh - height) // 2
        self.splash.geometry(f"{width}x{height}+{x}+{y}")

        # Border biru tipis
        self.splash.configure(highlightbackground='#1E88E5',
                              highlightcolor='#1E88E5',
                              highlightthickness=2)

        # Frame utama
        main_frame = tk.Frame(self.splash, bg='#000000')
        main_frame.pack(fill=tk.BOTH, expand=True, padx=20, pady=20)

        # Load logo
        try:
            logo_path = resource_path('splash_logo.png')
            if os.path.exists(logo_path):
                try:
                    from PIL import Image, ImageTk
                    img = Image.open(logo_path)
                    img = img.resize((380, 380), Image.LANCZOS)
                    self.logo_img = ImageTk.PhotoImage(img)
                    logo_label = tk.Label(
                        main_frame,
                        image=self.logo_img,
                        bg='#000000',
                        borderwidth=0
                    )
                    logo_label.pack(pady=(10, 5))
                except ImportError:
                    self.logo_img = tk.PhotoImage(file=logo_path)
                    logo_label = tk.Label(
                        main_frame,
                        image=self.logo_img,
                        bg='#000000',
                        borderwidth=0
                    )
                    logo_label.pack(pady=(10, 5))
            else:
                tk.Label(
                    main_frame,
                    text="DharmawanToxi",
                    font=('Segoe UI', 36, 'bold'),
                    fg='#1E88E5',
                    bg='#000000'
                ).pack(pady=100)
        except Exception as e:
            print(f"Error loading logo: {e}")
            tk.Label(
                main_frame,
                text="DharmawanToxi",
                font=('Segoe UI', 36, 'bold'),
                fg='#1E88E5',
                bg='#000000'
            ).pack(pady=100)

        # Nama aplikasi
        tk.Label(
            main_frame,
            text="APLIKASI PENGUKURAN LAHAN GPS",
            font=('Segoe UI', 13, 'bold'),
            fg='#FFFFFF',
            bg='#000000'
        ).pack(pady=(5, 2))

        # Versi
        tk.Label(
            main_frame,
            text="Version 2.0",
            font=('Segoe UI', 11),
            fg='#888888',
            bg='#000000'
        ).pack()

        # Loading text
        self.loading_var = tk.StringVar(value="Memuat aplikasi...")
        tk.Label(
            main_frame,
            textvariable=self.loading_var,
            font=('Segoe UI', 11),
            fg='#1E88E5',
            bg='#000000'
        ).pack(pady=(15, 8))

        # ═══════════════════════════════════════════════
        # CUSTOM PROGRESS BAR PAKAI CANVAS
        # (Tidak pakai ttk, biar tidak konflik ttkbootstrap)
        # ═══════════════════════════════════════════════
        self.progress_width = 400
        self.progress_height = 8

        self.progress_canvas = tk.Canvas(
            main_frame,
            width=self.progress_width,
            height=self.progress_height,
            bg='#1a1a1a',
            highlightthickness=0,
            borderwidth=0
        )
        self.progress_canvas.pack(pady=5)

        # Background rounded rectangle
        self.progress_bg = self.progress_canvas.create_rectangle(
            0, 0, self.progress_width, self.progress_height,
            fill='#1a1a1a', outline='#1a1a1a'
        )

        # Progress bar aktif (biru)
        self.progress_bar = self.progress_canvas.create_rectangle(
            0, 0, 0, self.progress_height,
            fill='#1E88E5', outline='#1E88E5'
        )

        # Copyright
        tk.Label(
            main_frame,
            text="© 2026 DharmawanToxi. All rights reserved.",
            font=('Segoe UI', 10),
            fg='#555555',
            bg='#000000'
        ).pack(side=tk.BOTTOM, pady=8)

        # Bring to front
        self.splash.attributes('-topmost', True)
        self.splash.update()

        # Mulai animasi progress
        self._start_progress()

    def _start_progress(self):
        """Animasi progress bar dari 0 sampai 100"""
        step_time = self.duration // 100
        self._current = 0

        loading_texts = [
            "Memuat aplikasi...",
            "Menghubungkan database...",
            "Menyiapkan komponen...",
            "Memuat modul GPS...",
            "Hampir siap..."
        ]

        def update_progress():
            self._current += 1

            # Update progress bar dengan animasi smooth
            new_width = int(
                (self._current / 100) * self.progress_width)
            self.progress_canvas.coords(
                self.progress_bar,
                0, 0, new_width, self.progress_height
            )

            # Ganti teks loading
            idx = min(self._current // 20, len(loading_texts) - 1)
            self.loading_var.set(loading_texts[idx])

            if self._current < 100:
                self.splash.after(step_time, update_progress)

        update_progress()

    def show(self):
        """Tampilkan splash lalu tutup otomatis"""
        self.splash.after(self.duration, self.close)
        self.splash.mainloop()

    def close(self):
        """Tutup splash screen"""
        try:
            self.splash.destroy()
        except Exception:
            pass


def show_splash(duration=3000):
    """Fungsi helper untuk menampilkan splash screen"""
    splash = SplashScreen(duration=duration)
    splash.show()


if __name__ == "__main__":
    show_splash(3000)