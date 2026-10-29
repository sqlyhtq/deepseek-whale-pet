package com.dsh.whalepet;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * 只读的桌面感知：找出「桌宠脚下」是哪个图标。
 * 不点击、不注入，仅读控件树。
 */
public class PetA11yService extends AccessibilityService {

    private static final String TAG = "WhalePet";
    private static PetA11yService inst;

    public static boolean isReady() {
        return inst != null;
    }

    /** 返回 (x,y) 处最具体的、带文字/描述的节点标签，找不到返回 null。 */
    public static String labelAt(int x, int y) {
        PetA11yService s = inst;
        if (s == null) return null;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            String r = pick(root, x, y, 0);
            if (r != null) return r;
            // 活动窗口可能是我们自己的悬浮窗，退回去扫所有应用窗口
            for (AccessibilityWindowInfo w : s.getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo wr = w.getRoot();
                String t = pick(wr, x, y, 0);
                if (t != null) return t;
            }
        } catch (Throwable t) {
            Log.w(TAG, "labelAt failed: " + t);
        }
        return null;
    }

    /** 收集桌面图标（带文字/描述的叶子节点）在屏幕上的矩形。 */
    public static java.util.List<Rect> iconRects() {
        java.util.List<Rect> out = new java.util.ArrayList<>();
        PetA11yService s = inst;
        if (s == null) return out;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) {
                for (AccessibilityWindowInfo w : s.getWindows()) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        root = w.getRoot();
                        if (root != null) break;
                    }
                }
            }
            collect(root, out, 0);
        } catch (Throwable t) {
            Log.w(TAG, "iconRects failed: " + t);
        }
        return out;
    }

    private static void collect(AccessibilityNodeInfo n, java.util.List<Rect> out, int depth) {
        if (n == null || depth > 30 || out.size() > 200) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        boolean hasLabel = textOf(n) != null;
        if (hasLabel && r.width() >= 60 && r.height() >= 60 && r.width() <= 400 && r.height() <= 400) {
            out.add(r);
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    /** 找某个标签（应用名）在屏幕上的矩形；找不到返回 null。 */
    public static Rect findLabel(String label) {
        PetA11yService s = inst;
        if (s == null || label == null || label.isEmpty()) return null;
        try {
            // 只在桌面（launcher）窗口里找，避免匹配到文件管理器里的同名文件
            AccessibilityNodeInfo root = launcherRoot(s);
            if (root == null) return null;
            return search(root, label, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static AccessibilityNodeInfo launcherRoot(PetA11yService s) {
        AccessibilityNodeInfo active = s.getRootInActiveWindow();
        if (active != null && isLauncher(active)) return active;
        for (AccessibilityWindowInfo w : s.getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && isLauncher(r)) return r;
        }
        return null;
    }

    private static boolean isLauncher(AccessibilityNodeInfo n) {
        CharSequence p = n.getPackageName();
        if (p == null) return false;
        String s = p.toString();
        return s.contains("launcher") || s.contains(".home") || s.equals("com.android.launcher3");
    }

    /** 用无障碍滚动（不注入触摸手势，避免和「上滑开抽屉」冲突）。 */
    public static boolean scrollForward() {
        PetA11yService s = inst;
        if (s == null) return false;
        try {
            AccessibilityNodeInfo root = launcherRoot(s);
            if (root == null) return false;
            AccessibilityNodeInfo scroller = findScrollable(root, 0);
            if (scroller == null) return false;
            boolean ok = scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            Log.i(TAG, "a11y scrollForward=" + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "scrollForward failed: " + t);
            return false;
        }
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 20) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** 当前页面所有可见标签拼成的指纹，用来判断滚动是否有效。 */
    public static String pageFingerprint() {
        PetA11yService s = inst;
        if (s == null) return "";
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return "";
            StringBuilder sb = new StringBuilder();
            collectText(root, sb, 0);
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void collectText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 26 || sb.length() > 4000) return;
        String t = textOf(n);
        if (t != null) sb.append(t).append('|');
        for (int i = 0; i < n.getChildCount(); i++) collectText(n.getChild(i), sb, depth + 1);
    }

    private static Rect search(AccessibilityNodeInfo n, String label, int depth) {
        if (n == null || depth > 30) return null;
        String t = textOf(n);
        if (t != null && (t.equals(label) || t.contains(label))) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > 40 && r.height() > 40) return r;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            Rect r = search(n.getChild(i), label, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static String pick(AccessibilityNodeInfo node, int x, int y, int depth) {
        if (node == null || depth > 24) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        AccessibilityNodeInfo best = bestChild(node, x, y, depth + 1, r);
        return textOf(best != null ? best : node);
    }

    private static AccessibilityNodeInfo bestChild(AccessibilityNodeInfo node, int x, int y, int depth, Rect parentRect) {
        if (node == null || depth > 24) return null;
        AccessibilityNodeInfo best = null;
        Rect bestRect = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            Rect r = new Rect();
            c.getBoundsInScreen(r);
            if (!r.contains(x, y)) continue;
            if (best == null || (r.width() * (long) r.height()) < (bestRect.width() * (long) bestRect.height())) {
                best = c;
                bestRect = r;
            }
        }
        if (best == null) return null;
        AccessibilityNodeInfo deeper = bestChild(best, x, y, depth + 1, bestRect);
        return deeper != null ? deeper : best;
    }

    private static String textOf(AccessibilityNodeInfo n) {
        if (n == null) return null;
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t == null || t.length() == 0) return null;
        String s = t.toString().trim();
        if (s.isEmpty() || s.length() > 24) return null;
        return s;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        Log.i(TAG, "a11y connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只读感知，事件不用处理
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        inst = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }
}
