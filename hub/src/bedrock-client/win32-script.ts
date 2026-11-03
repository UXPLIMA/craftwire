/**
 * The PowerShell helper the hub runs to see and drive the Minecraft for Windows window: a C# class (compiled once
 * by Add-Type, so C# 5 as Windows PowerShell's compiler takes it) for the Win32 calls, and a loop that answers one
 * JSON request per stdin line with one JSON line on stdout. Kept as a string so the package ships no extra file;
 * the manager writes it to the hub's home before starting it.
 */
const CSHARP = String.raw`
using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

public static class CwWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)] public struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public INPUTUNION u; }

    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a, uint b, bool attach);
    [DllImport("user32.dll")] static extern uint SendInput(uint n, INPUT[] inputs, int size);
    [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] static extern uint MapVirtualKey(uint code, uint mapType);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();

    const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    const uint KEY_EXTENDED = 0x1, KEY_UP = 0x2, KEY_UNICODE = 0x4, KEY_SCANCODE = 0x8;
    const uint PW_CLIENTONLY_FULLCONTENT = 3;
    const ushort VK_MENU = 0x12;

    public static string LastMethod = "";

    public static void Init() { SetProcessDPIAware(); }

    /** The main window of the first process with that name that has one, else zero. */
    public static IntPtr Find(string process) {
        foreach (Process p in Process.GetProcessesByName(process)) {
            if (p.MainWindowHandle != IntPtr.Zero) return p.MainWindowHandle;
        }
        return IntPtr.Zero;
    }

    static IntPtr Need(string process) {
        IntPtr h = Find(process);
        if (h == IntPtr.Zero) throw new InvalidOperationException("NOT_RUNNING");
        return h;
    }

    /** The client area on screen: x, y, width, height in physical pixels. */
    public static int[] Client(string process) {
        IntPtr h = Need(process);
        RECT r;
        GetClientRect(h, out r);
        POINT p = new POINT();
        ClientToScreen(h, ref p);
        return new int[] { p.X, p.Y, r.Right - r.Left, r.Bottom - r.Top };
    }

    public static bool Focused(string process) {
        return GetForegroundWindow() == Find(process);
    }

    /** Brings the window to the front; Windows lets a process do that after an Alt press and with input attached. */
    public static void Focus(string process) {
        IntPtr h = Need(process);
        if (IsIconic(h)) ShowWindow(h, 9);
        uint pid;
        uint fg = GetWindowThreadProcessId(GetForegroundWindow(), out pid);
        uint me = GetCurrentThreadId();
        Key(VK_MENU, 0, false);
        AttachThreadInput(me, fg, true);
        BringWindowToTop(h);
        SetForegroundWindow(h);
        AttachThreadInput(me, fg, false);
        Thread.Sleep(120);
    }

    /** The client area as a PNG (base64), at most maxWidth wide; PrintWindow first, a screen copy when it is black. */
    public static string Capture(string process, int maxWidth) {
        IntPtr h = Need(process);
        int[] c = Client(process);
        if (c[2] <= 0 || c[3] <= 0) throw new InvalidOperationException("NO_AREA");
        using (Bitmap bmp = new Bitmap(c[2], c[3], PixelFormat.Format32bppArgb)) {
            using (Graphics g = Graphics.FromImage(bmp)) {
                IntPtr dc = g.GetHdc();
                PrintWindow(h, dc, PW_CLIENTONLY_FULLCONTENT);
                g.ReleaseHdc(dc);
            }
            LastMethod = "printwindow";
            if (Black(bmp)) {
                Focus(process);
                c = Client(process);
                using (Graphics g = Graphics.FromImage(bmp)) g.CopyFromScreen(c[0], c[1], 0, 0, new Size(c[2], c[3]));
                LastMethod = "screen";
            }
            return Png(bmp, maxWidth);
        }
    }

    /** True when a 16 x 16 grid of samples is all near black: what PrintWindow gives for some DirectX windows. */
    static bool Black(Bitmap b) {
        for (int i = 1; i <= 16; i++) {
            for (int j = 1; j <= 16; j++) {
                Color px = b.GetPixel(b.Width * i / 17, b.Height * j / 17);
                if (px.R > 8 || px.G > 8 || px.B > 8) return false;
            }
        }
        return true;
    }

    static string Png(Bitmap bmp, int maxWidth) {
        Bitmap output = bmp;
        if (maxWidth > 0 && bmp.Width > maxWidth) {
            int height = (int) Math.Round((double) bmp.Height * maxWidth / bmp.Width);
            output = new Bitmap(maxWidth, height, PixelFormat.Format32bppArgb);
            using (Graphics g = Graphics.FromImage(output)) {
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.DrawImage(bmp, 0, 0, maxWidth, height);
            }
        }
        try {
            using (MemoryStream ms = new MemoryStream()) {
                output.Save(ms, ImageFormat.Png);
                return Convert.ToBase64String(ms.ToArray());
            }
        } finally {
            if (output != bmp) output.Dispose();
        }
    }

    /** A key by virtual-key code, sent as its scan code (games read scan codes); held for holdMs. */
    public static void Key(ushort vk, int holdMs, bool extended) {
        ushort scan = (ushort) MapVirtualKey(vk, 0);
        uint flags = KEY_SCANCODE | (extended ? KEY_EXTENDED : 0);
        SendKey(0, scan, flags);
        Thread.Sleep(Math.Max(holdMs, 30));
        SendKey(0, scan, flags | KEY_UP);
    }

    /** Text as Unicode key events, which reach text fields whatever the keyboard layout. */
    public static void Text(string s) {
        foreach (char ch in s) {
            SendKey(0, ch, KEY_UNICODE);
            SendKey(0, ch, KEY_UNICODE | KEY_UP);
            Thread.Sleep(12);
        }
    }

    static void SendKey(ushort vk, ushort scan, uint flags) {
        INPUT[] i = new INPUT[1];
        i[0].type = INPUT_KEYBOARD;
        i[0].u.ki.wVk = vk;
        i[0].u.ki.wScan = scan;
        i[0].u.ki.dwFlags = flags;
        SendInput(1, i, Marshal.SizeOf(typeof(INPUT)));
    }

    /** A click at a screen position: left, right or middle. */
    public static void Click(int x, int y, string button) {
        uint down = 0x2, up = 0x4;
        if (button == "right") { down = 0x8; up = 0x10; }
        if (button == "middle") { down = 0x20; up = 0x40; }
        SetCursorPos(x, y);
        Thread.Sleep(40);
        SendMouse(down, 0);
        Thread.Sleep(40);
        SendMouse(up, 0);
    }

    /** Wheel clicks at a screen position: positive scrolls up. */
    public static void Scroll(int x, int y, int clicks) {
        SetCursorPos(x, y);
        Thread.Sleep(40);
        SendMouse(0x800, (uint) (clicks * 120));
    }

    static void SendMouse(uint flags, uint data) {
        INPUT[] i = new INPUT[1];
        i[0].type = INPUT_MOUSE;
        i[0].u.mi.dwFlags = flags;
        i[0].u.mi.mouseData = data;
        SendInput(1, i, Marshal.SizeOf(typeof(INPUT)));
    }

    /** Asks the window to close, as its close button does. */
    public static bool Close(string process) {
        foreach (Process p in Process.GetProcessesByName(process)) {
            if (p.MainWindowHandle != IntPtr.Zero) return p.CloseMainWindow();
        }
        return false;
    }
}
`;

/** The request loop: errors come back as {id, error} so one bad request never ends the helper. */
const LOOP = String.raw`
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -TypeDefinition $cs -ReferencedAssemblies System.Drawing
[CwWin]::Init()
[Console]::Out.WriteLine('{"ready":true}')
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  $q = $null
  try {
    $q = $line | ConvertFrom-Json
    $r = @{ id = $q.id }
    switch ($q.op) {
      'open' { Start-Process $q.url }
      'find' {
        $r.running = ([CwWin]::Find($q.process) -ne [IntPtr]::Zero)
        if ($r.running) { $r.client = [CwWin]::Client($q.process); $r.focused = [CwWin]::Focused($q.process) }
      }
      'capture' { $r.png = [CwWin]::Capture($q.process, [int]$q.maxWidth); $r.method = [CwWin]::LastMethod; $r.client = [CwWin]::Client($q.process) }
      'focus' { [CwWin]::Focus($q.process) }
      'key' { [CwWin]::Focus($q.process); [CwWin]::Key([uint16]$q.vk, [int]$q.holdMs, [bool]$q.extended) }
      'text' { [CwWin]::Focus($q.process); [CwWin]::Text([string]$q.text) }
      'click' { [CwWin]::Focus($q.process); [CwWin]::Click([int]$q.x, [int]$q.y, [string]$q.button) }
      'scroll' { [CwWin]::Focus($q.process); [CwWin]::Scroll([int]$q.x, [int]$q.y, [int]$q.clicks) }
      'close' { $r.closed = [CwWin]::Close($q.process) }
      default { throw "unknown op $($q.op)" }
    }
  } catch {
    $id = $null
    if ($null -ne $q) { $id = $q.id }
    $r = @{ id = $id; error = $_.Exception.Message }
  }
  [Console]::Out.WriteLine(($r | ConvertTo-Json -Compress -Depth 4))
}
`;

/** The helper script: the C# source in a single-quoted here-string (no PowerShell expansion inside), then the loop. */
export function helperScript(): string {
  return `$cs = @'\r\n${CSHARP.trim()}\r\n'@\r\n${LOOP.trim()}\r\n`;
}
