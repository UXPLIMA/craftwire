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
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] static extern uint MapVirtualKey(uint code, uint mapType);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();

    const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    const uint KEY_UP = 0x2;
    const uint PW_CLIENTONLY_FULLCONTENT = 3;
    const ushort VK_MENU = 0x12;
    const uint WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, WM_CHAR = 0x102;

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

    /**
     * Brings the window to the front, or throws NOT_FOCUSED: input must never land in whatever window is in front
     * instead. Windows lets a process take the foreground after an Alt press (a virtual-key event, as keybd_event
     * sends it) with its input attached to the foreground thread; minimizing and restoring is the fallback.
     */
    public static void Focus(string process) {
        IntPtr h = Need(process);
        if (GetForegroundWindow() == h) return;
        if (IsIconic(h)) ShowWindow(h, 9);
        uint pid;
        uint fg = GetWindowThreadProcessId(GetForegroundWindow(), out pid);
        uint me = GetCurrentThreadId();
        SendKey(VK_MENU, 0, 0);
        SendKey(VK_MENU, 0, KEY_UP);
        AttachThreadInput(me, fg, true);
        BringWindowToTop(h);
        SetForegroundWindow(h);
        AttachThreadInput(me, fg, false);
        Thread.Sleep(150);
        if (GetForegroundWindow() != h) {
            ShowWindow(h, 6);
            ShowWindow(h, 9);
            SetForegroundWindow(h);
            Thread.Sleep(400);
        }
        if (GetForegroundWindow() != h) throw new InvalidOperationException("NOT_FOCUSED");
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

    /**
     * A key posted to the window as WM_KEYDOWN / WM_KEYUP, held for holdMs: it reaches the game in the background,
     * without the focus or the user's keyboard (measured on 1.26.60: menus, dialogs and the chat take it). lParam
     * carries the repeat count 1, the scan code, the extended bit and, on the way up, the previous-state and
     * transition bits.
     */
    public static void PostKey(string process, ushort vk, int holdMs, bool extended) {
        IntPtr h = Need(process);
        uint l = 1u | (MapVirtualKey(vk, 0) << 16) | (extended ? 1u << 24 : 0u);
        PostMessage(h, WM_KEYDOWN, (IntPtr) vk, (IntPtr) (long) l);
        Thread.Sleep(Math.Max(holdMs, 30));
        PostMessage(h, WM_KEYUP, (IntPtr) vk, (IntPtr) (long) (l | 3u << 30));
    }

    /** Text posted as WM_CHAR, one character at a time, in the background like PostKey. */
    public static void PostText(string process, string s) {
        IntPtr h = Need(process);
        foreach (char ch in s) {
            PostMessage(h, WM_CHAR, (IntPtr) ch, (IntPtr) 1);
            Thread.Sleep(15);
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
      'key' { [CwWin]::PostKey($q.process, [uint16]$q.vk, [int]$q.holdMs, [bool]$q.extended) }
      'text' { [CwWin]::PostText($q.process, [string]$q.text) }
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
