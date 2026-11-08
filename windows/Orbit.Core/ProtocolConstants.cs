namespace Orbit.Core;

public static class ProtocolConstants
{
    public const int Version = 1;
    public const int DefaultPort = 47717;
    public const string KeyHeader = "X-Orbit-Key";
    public const string KeyQuery = "k";
    public const string ServiceType = "_orbit._tcp";
    public const int MaxFilesPerSession = 2000;
    public static readonly TimeSpan ApprovalTimeout = TimeSpan.FromSeconds(90);
    /// <summary>
    /// After a Shortcuts upload is approved, further uploads from the same address join that transfer
    /// while they keep coming: 60 s since the last byte, enough for one Share Sheet run, not for later sessions.
    /// </summary>
    public static readonly TimeSpan AdHocTrustWindow = TimeSpan.FromSeconds(60);
}

public static class ErrorCodes
{
    public const string Unauthorized = "unauthorized";
    public const string NotLocal = "not_local";
    public const string ReceivingDisabled = "receiving_disabled";
    public const string Declined = "declined";
    public const string Expired = "expired";
    public const string NotFound = "not_found";
    public const string OffsetMismatch = "offset_mismatch";
    public const string InsufficientStorage = "insufficient_storage";
    public const string Canceled = "canceled";
    public const string InvalidRequest = "invalid_request";
    public const string RateLimited = "rate_limited";
    public const string WriteFailed = "write_failed";
    public const string UntrustedNetwork = "untrusted_network";
}
