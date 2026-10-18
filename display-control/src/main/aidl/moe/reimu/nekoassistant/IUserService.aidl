package moe.reimu.nekoassistant;


interface IUserService {
    void destroy() = 16777114;
    boolean startDisplay(int width, int height, int dpi, in android.view.Surface surface) = 1;

    void startActivity(in android.content.Intent intent, boolean forceStop) = 3;
    String getFocusedPackageName() = 4;

    boolean injectInputEvent(in android.view.InputEvent input) = 5;
    boolean injectSwipeEvent(float x1, float y1, float x2, float y2, long duration) = 6;
    boolean injectTapEvent(float x, float y) = 7;
    boolean injectKeyPressEvent(int keyCode) = 8;

    boolean stopDisplay() = 9;

    // Display-specific API. Legacy transactions above retain their default-display behavior.
    int createDisplay(in android.view.Surface surface) = 10;
    boolean releaseDisplay(int displayId) = 11;
    int[] getDisplayIds() = 12;
    void startActivityOnDisplay(int displayId, in android.content.Intent intent) = 13;
    String getFocusedPackageOnDisplay(int displayId) = 14;
    boolean injectInputEventOnDisplay(int displayId, in android.view.InputEvent input) = 15;
    boolean injectSwipeOnDisplay(int displayId, float x1, float y1, float x2, float y2, long duration) = 16;
    boolean injectTapOnDisplay(int displayId, float x, float y) = 17;
    boolean injectKeyOnDisplay(int displayId, int keyCode) = 18;
    int[] getMainDisplayMetrics() = 19;
    String getRecentTasksJson() = 20;
    void moveRecentTaskToDisplay(int taskId, int displayId) = 21;
    void setNotificationListenerAccess(String component, int userId, IBinder owner, boolean enabled) = 22;
    void sendNotificationIntent(in android.app.PendingIntent pendingIntent, int displayId, in android.content.Intent fillIn) = 23;
    // Transaction 24 (clipboard text) is retired; do not reuse it.
    void setAccessibilityServiceAccess(String component, int userId, IBinder owner, boolean enabled) = 25;
    String getClipboardText() = 26;
    void setClipboardTextVerified(String text) = 27;
}
