/**
 * The C# part of the Windows helper that takes the game window's picture with Windows.Graphics.Capture: the way
 * screen recorders capture one window, which works while the window sits behind others and never needs the focus
 * (PrintWindow gives black for the game's DirectX surface, and a screen copy needs the window in front). C# 5 for
 * Windows PowerShell's Add-Type, so the WinRT and Direct3D 11 objects are called through their vtables; slot numbers
 * and IIDs are from the Windows SDK headers (windows.graphics.capture.h, d3d11.h).
 */
export const CAPTURE_CSHARP = String.raw`
public static class CwCapture {
    [StructLayout(LayoutKind.Sequential)] public struct SizeInt32 { public int Width, Height; }
    [StructLayout(LayoutKind.Sequential)]
    public struct TexDesc {
        public uint Width, Height, MipLevels, ArraySize, Format, SampleCount, SampleQuality, Usage, BindFlags, CpuAccess, Misc;
    }
    [StructLayout(LayoutKind.Sequential)] public struct Mapped { public IntPtr Data; public uint RowPitch, DepthPitch; }
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }

    delegate int CreateForWindowFn(IntPtr self, IntPtr window, ref Guid iid, out IntPtr result);
    delegate int GetSizeFn(IntPtr self, out SizeInt32 size);
    delegate int CreatePoolFn(IntPtr self, IntPtr device, int format, int buffers, SizeInt32 size, out IntPtr pool);
    delegate int OutFn(IntPtr self, out IntPtr result);
    delegate int InOutFn(IntPtr self, IntPtr arg, out IntPtr result);
    delegate int VoidFn(IntPtr self);
    delegate int SetBoolFn(IntPtr self, byte value);
    delegate int GetInterfaceFn(IntPtr self, ref Guid iid, out IntPtr result);
    delegate void GetDescFn(IntPtr self, out TexDesc desc);
    delegate int CreateTextureFn(IntPtr self, ref TexDesc desc, IntPtr data, out IntPtr texture);
    delegate void CopyFn(IntPtr self, IntPtr dst, IntPtr src);
    delegate int MapFn(IntPtr self, IntPtr resource, uint sub, int type, uint flags, out Mapped mapped);
    delegate void UnmapFn(IntPtr self, IntPtr resource, uint sub);

    [DllImport("d3d11.dll")]
    static extern int D3D11CreateDevice(IntPtr adapter, int driverType, IntPtr software, uint flags, IntPtr levels, uint count,
        uint sdk, out IntPtr device, out int level, out IntPtr context);
    [DllImport("d3d11.dll")] static extern int CreateDirect3D11DeviceFromDXGIDevice(IntPtr dxgi, out IntPtr device);
    [DllImport("combase.dll")] static extern int RoGetActivationFactory(IntPtr classId, ref Guid iid, out IntPtr factory);
    [DllImport("combase.dll", CharSet = CharSet.Unicode)] static extern int WindowsCreateString(string s, int length, out IntPtr h);
    [DllImport("combase.dll")] static extern int WindowsDeleteString(IntPtr h);
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attribute, out RECT r, int size);
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr h, ref POINT p);

    static readonly Guid ItemInterop = new Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356");
    static readonly Guid Item = new Guid("79C3F95B-31F7-4EC2-A464-632EF5D30760");
    static readonly Guid PoolStatics2 = new Guid("589B103F-6BBC-5DF5-A991-02E28B3B66D5");
    static readonly Guid Session2 = new Guid("2C39AE40-7D2E-5044-804E-8B6799D4CF9E");
    static readonly Guid Session3 = new Guid("F2CDD966-22AE-5EA1-9596-3A289344C3BE");
    static readonly Guid Closable = new Guid("30D5A829-7FA4-4026-83BB-D75BAE4EA99E");
    static readonly Guid DxgiAccess = new Guid("A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1");
    static readonly Guid Texture2D = new Guid("6F15AAF2-D208-4E89-9AB4-489535D34F9C");
    static readonly Guid DxgiDevice = new Guid("54EC77FA-1377-44E6-8C32-88FD5F44C84C");

    const int HARDWARE = 1, WARP = 5;
    const uint BGRA_SUPPORT = 0x20, SDK_VERSION = 7;
    const int B8G8R8A8 = 87;
    const uint STAGING = 3, CPU_READ = 0x20000;
    const int MAP_READ = 1;
    const int EXTENDED_FRAME_BOUNDS = 9;

    static IntPtr device, context, graphicsDevice;

    /** The window's client area, as the compositor last drew it; throws NO_FRAME when none comes within timeoutMs. */
    public static Bitmap Window(IntPtr h, int timeoutMs) {
        Init();
        IntPtr interop = IntPtr.Zero, item = IntPtr.Zero, statics = IntPtr.Zero, pool = IntPtr.Zero, session = IntPtr.Zero;
        IntPtr frame = IntPtr.Zero, surface = IntPtr.Zero, access = IntPtr.Zero, texture = IntPtr.Zero, staging = IntPtr.Zero;
        try {
            interop = Factory("Windows.Graphics.Capture.GraphicsCaptureItem", ItemInterop);
            Guid itemIid = Item;
            Check(Slot<CreateForWindowFn>(interop, 3)(interop, h, ref itemIid, out item), "CreateForWindow");
            SizeInt32 size;
            Check(Slot<GetSizeFn>(item, 7)(item, out size), "GraphicsCaptureItem.Size");
            statics = Factory("Windows.Graphics.Capture.Direct3D11CaptureFramePool", PoolStatics2);
            Check(Slot<CreatePoolFn>(statics, 6)(statics, graphicsDevice, B8G8R8A8, 1, size, out pool), "CreateFreeThreaded");
            Check(Slot<InOutFn>(pool, 10)(pool, item, out session), "CreateCaptureSession");
            // No cursor in the picture, and no yellow capture border around the user's window (Windows 11).
            SetBool(session, Session2, false);
            SetBool(session, Session3, false);
            Check(Slot<VoidFn>(session, 6)(session), "StartCapture");
            OutFn next = Slot<OutFn>(pool, 7);
            for (int waited = 0; ; waited += 10) {
                Check(next(pool, out frame), "TryGetNextFrame");
                if (frame != IntPtr.Zero) break;
                if (waited >= timeoutMs) throw new InvalidOperationException("NO_FRAME");
                Thread.Sleep(10);
            }
            Check(Slot<OutFn>(frame, 6)(frame, out surface), "Direct3D11CaptureFrame.Surface");
            Guid accessIid = DxgiAccess, textureIid = Texture2D;
            Check(Marshal.QueryInterface(surface, ref accessIid, out access), "IDirect3DDxgiInterfaceAccess");
            Check(Slot<GetInterfaceFn>(access, 3)(access, ref textureIid, out texture), "GetInterface");
            TexDesc d;
            Slot<GetDescFn>(texture, 10)(texture, out d);
            d.MipLevels = 1; d.ArraySize = 1; d.SampleCount = 1; d.SampleQuality = 0;
            d.Usage = STAGING; d.BindFlags = 0; d.CpuAccess = CPU_READ; d.Misc = 0;
            Check(Slot<CreateTextureFn>(device, 5)(device, ref d, IntPtr.Zero, out staging), "CreateTexture2D");
            Slot<CopyFn>(context, 47)(context, staging, texture);
            Mapped m;
            Check(Slot<MapFn>(context, 14)(context, staging, 0, MAP_READ, 0, out m), "Map");
            try {
                return Crop(h, m, (int) d.Width, (int) d.Height);
            } finally {
                Slot<UnmapFn>(context, 15)(context, staging, 0);
            }
        } finally {
            Close(frame);
            Close(session);
            Close(pool);
            foreach (IntPtr p in new IntPtr[] { staging, texture, access, surface, frame, session, pool, statics, item, interop }) {
                if (p != IntPtr.Zero) Marshal.Release(p);
            }
        }
    }

    /** One Direct3D device for every capture: the hardware one, or WARP where there is no GPU (a VM, a server). */
    static void Init() {
        if (graphicsDevice != IntPtr.Zero) return;
        int level;
        int hr = D3D11CreateDevice(IntPtr.Zero, HARDWARE, IntPtr.Zero, BGRA_SUPPORT, IntPtr.Zero, 0, SDK_VERSION,
            out device, out level, out context);
        if (hr < 0) {
            hr = D3D11CreateDevice(IntPtr.Zero, WARP, IntPtr.Zero, BGRA_SUPPORT, IntPtr.Zero, 0, SDK_VERSION, out device, out level, out context);
        }
        Check(hr, "D3D11CreateDevice");
        IntPtr dxgi;
        Guid g = DxgiDevice;
        Check(Marshal.QueryInterface(device, ref g, out dxgi), "IDXGIDevice");
        try {
            Check(CreateDirect3D11DeviceFromDXGIDevice(dxgi, out graphicsDevice), "CreateDirect3D11DeviceFromDXGIDevice");
        } finally {
            Marshal.Release(dxgi);
        }
    }

    /** The frame holds the window's visible frame (title bar included); the picture is its client area. */
    static Bitmap Crop(IntPtr h, Mapped m, int textureWidth, int textureHeight) {
        RECT bounds, client;
        Check(DwmGetWindowAttribute(h, EXTENDED_FRAME_BOUNDS, out bounds, Marshal.SizeOf(typeof(RECT))), "DwmGetWindowAttribute");
        GetClientRect(h, out client);
        POINT origin = new POINT();
        ClientToScreen(h, ref origin);
        int ox = Math.Max(0, origin.X - bounds.Left), oy = Math.Max(0, origin.Y - bounds.Top);
        int w = Math.Min(client.Right - client.Left, textureWidth - ox), ht = Math.Min(client.Bottom - client.Top, textureHeight - oy);
        if (w <= 0 || ht <= 0) throw new InvalidOperationException("NO_AREA");
        Bitmap b = new Bitmap(w, ht, PixelFormat.Format32bppRgb);
        BitmapData bd = b.LockBits(new Rectangle(0, 0, w, ht), ImageLockMode.WriteOnly, PixelFormat.Format32bppRgb);
        try {
            byte[] row = new byte[w * 4];
            for (int y = 0; y < ht; y++) {
                Marshal.Copy(new IntPtr(m.Data.ToInt64() + (long) (oy + y) * m.RowPitch + ox * 4), row, 0, row.Length);
                Marshal.Copy(row, 0, new IntPtr(bd.Scan0.ToInt64() + (long) y * bd.Stride), row.Length);
            }
        } finally {
            b.UnlockBits(bd);
        }
        return b;
    }

    static IntPtr Factory(string runtimeClass, Guid iid) {
        IntPtr name, factory;
        Check(WindowsCreateString(runtimeClass, runtimeClass.Length, out name), "WindowsCreateString");
        try {
            Check(RoGetActivationFactory(name, ref iid, out factory), "RoGetActivationFactory " + runtimeClass);
            return factory;
        } finally {
            WindowsDeleteString(name);
        }
    }

    /** Sets a boolean property of a newer session interface; older Windows lacks it, and the default does no harm. */
    static void SetBool(IntPtr session, Guid iid, bool value) {
        IntPtr p;
        if (Marshal.QueryInterface(session, ref iid, out p) < 0) return;
        try {
            Slot<SetBoolFn>(p, 7)(p, value ? (byte) 1 : (byte) 0);
        } finally {
            Marshal.Release(p);
        }
    }

    static void Close(IntPtr obj) {
        if (obj == IntPtr.Zero) return;
        Guid g = Closable;
        IntPtr c;
        if (Marshal.QueryInterface(obj, ref g, out c) < 0) return;
        try {
            Slot<VoidFn>(c, 6)(c);
        } finally {
            Marshal.Release(c);
        }
    }

    static T Slot<T>(IntPtr obj, int index) where T : class {
        IntPtr fn = Marshal.ReadIntPtr(Marshal.ReadIntPtr(obj), index * IntPtr.Size);
        return (T) (object) Marshal.GetDelegateForFunctionPointer(fn, typeof(T));
    }

    static void Check(int hr, string what) {
        if (hr < 0) throw new InvalidOperationException(what + " failed (0x" + hr.ToString("X8") + ")");
    }
}
`;
