using Orbit.Core;

namespace Orbit.Core.Tests;

public class SanitizerTests
{
    [Theory]
    [InlineData("../../Windows/System32/evil.dll", "evil.dll")]
    [InlineData("..\\..\\boot.ini", "boot.ini")]
    [InlineData("C:\\Users\\x\\a.txt", "a.txt")]
    [InlineData("/etc/passwd", "passwd")]
    [InlineData("..", "file")]
    [InlineData("", "file")]
    [InlineData("   ", "file")]
    [InlineData("CON", "_CON")]
    [InlineData("con.txt", "_con.txt")]
    [InlineData("LPT1.tar.gz", "_LPT1.tar.gz")]
    [InlineData("a<b>c:d\"e|f?g*h.jpg", "abcdefgh.jpg")]
    [InlineData("trailing.dots...", "trailing.dots")]
    [InlineData(".hidden", "hidden")]
    [InlineData("IMG_0001.HEIC", "IMG_0001.HEIC")]
    [InlineData("Отпуск 2026 🌴.mov", "Отпуск 2026 🌴.mov")]
    [InlineData("evil\u202Egpj.exe", "evilgpj.exe")]
    [InlineData("tab\tand\nnewline.txt", "tabandnewline.txt")]
    public void Sanitize_ProducesSafeLeafName(string input, string expected)
    {
        Assert.Equal(expected, FileNameSanitizer.Sanitize(input));
    }

    [Fact]
    public void Sanitize_LimitsLengthAndKeepsExtension()
    {
        var result = FileNameSanitizer.Sanitize(new string('a', 500) + ".jpeg");
        Assert.True(result.Length <= 180);
        Assert.EndsWith(".jpeg", result);
    }

    [Fact]
    public void MakeUnique_AppendsCounter()
    {
        var taken = new HashSet<string> { "a.jpg", "a (1).jpg" };
        Assert.Equal("a (2).jpg", FileNameSanitizer.MakeUnique("a.jpg", taken.Contains));
        Assert.Equal("b.jpg", FileNameSanitizer.MakeUnique("b.jpg", taken.Contains));
    }

    [Fact]
    public void NetworkTrust_ResolvesCategoryOfLanAddress()
    {
        if (Environment.GetEnvironmentVariable("CI") == "true") return; // hosted runners have no real network profile
        var trust = new NetworkTrust();
        trust.Refresh();
        Assert.Null(trust.LastError);
        var lan = NetworkUtil.GetLanAddresses().FirstOrDefault();
        if (lan is null) return;
        Assert.NotEqual(NetworkCategory.Unknown, trust.CategoryOf(lan));
    }

    [Theory]
    [InlineData("192.168.1.5", true)]
    [InlineData("10.0.0.1", true)]
    [InlineData("172.20.1.1", true)]
    [InlineData("172.32.1.1", false)]
    [InlineData("169.254.3.3", true)]
    [InlineData("127.0.0.1", true)]
    [InlineData("8.8.8.8", false)]
    [InlineData("fe80::1", true)]
    [InlineData("fd12::1", true)]
    [InlineData("2001:4860::8888", false)]
    [InlineData("::ffff:192.168.0.2", true)]
    public void IsLocalNetwork(string ip, bool expected)
    {
        Assert.Equal(expected, NetworkUtil.IsLocalNetwork(System.Net.IPAddress.Parse(ip)));
    }
}
