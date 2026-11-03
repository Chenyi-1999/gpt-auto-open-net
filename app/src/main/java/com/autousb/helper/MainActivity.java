package com.autousb.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int EXPORT_LOG = 20;
    private int ink, muted, navy, teal, surface, background, border;

    private ProbeStore store;
    private TextView details;
    private TextView guardValue;
    private TextView guardHint;
    private TextView usbValue;
    private TextView tetherValue;
    private TextView switchValue;
    private TextView resultValue;
    private TextView prepareValue;
    private TextView bootValue;
    private TextView overlayValue;
    private Button bootToggle;
    private Button verify, cancelVerification, startGuard, stopGuard;
    private boolean resumed;
    private int pollSeconds = 60;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable render = new Runnable() {
        @Override public void run() { renderStatus(); handler.postDelayed(this, 1000); }
    };
    private final Runnable changed = this::renderStatus;

    private String installedVersionName() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null || name.isEmpty() ? "未知" : name;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            return "未知";
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ink = getColor(R.color.text_primary);
        muted = getColor(R.color.text_secondary);
        navy = getColor(R.color.action_primary);
        teal = getColor(R.color.state_accent);
        surface = getColor(R.color.surface_card);
        background = getColor(R.color.surface_background);
        border = getColor(R.color.border_subtle);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        store = ProbeStore.get(this);
        pollSeconds = BootPreferences.pollSeconds(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        TextView title = text("USB 共享助手", 27, ink, true);
        page.addView(title);
        TextView version = text("本机辅助工具 · 版本 " + installedVersionName(), 14, muted, false);
        version.setPadding(0, dp(4), 0, dp(18));
        page.addView(version);

        LinearLayout guardCard = card(page);
        rowTitle(guardCard, "辅助守护", "手机端运行", teal);
        guardValue = text("状态读取中…", 16, ink, true);
        guardValue.setPadding(0, dp(10), 0, dp(12));
        guardCard.addView(guardValue);
        startGuard = addButton(guardCard, "启用辅助守护", true, v -> confirmGuard());
        stopGuard = addButton(guardCard, "停止守护", false, v -> GuardService.stopFromActivity(this));
        guardHint = text("USB 重新连接后按安全条件尝试开启共享。后台运行会显示持续通知，可从通知或此页停止。", 14, muted, false);
        guardHint.setPadding(0, dp(10), 0, 0);
        guardCard.addView(guardHint);

        LinearLayout stateCard = card(page);
        rowTitle(stateCard, "设备状态", "状态可能过期", teal);
        usbValue = addState(stateCard, "USB 数据连接", "未知");
        tetherValue = addState(stateCard, "共享接口观测", "未知");
        switchValue = addState(stateCard, "系统开关观测", "未知");
        TextView stateHint = text("接口状态只作观测线索；不代表本次开关操作已最终确认。", 13, muted, false);
        stateHint.setPadding(0, dp(8), 0, 0);
        stateCard.addView(stateHint);
        TextView network = text("电脑联网：未验证", 15, muted, true);
        network.setPadding(0, dp(8), 0, 0);
        stateCard.addView(network);

        LinearLayout resultCard = card(page);
        rowTitle(resultCard, "最近结果", "本机记录", teal);
        resultValue = text("尚未验证", 15, ink, false);
        resultValue.setPadding(0, dp(8), 0, 0);
        resultCard.addView(resultValue);

        LinearLayout preparation = card(page);
        rowTitle(preparation, "首次配置", "请检查", teal);
        prepareValue = text("先完成无障碍与通知准备。息屏恢复依赖本机系统行为，锁屏时需要等待解锁。", 15, ink, false);
        prepareValue.setPadding(0, dp(8), 0, dp(6));
        preparation.addView(prepareValue);
        addButton(preparation, "查看配置建议", false, v -> showSetupNotes());
        addButton(preparation, "打开共享设置", false, v -> {
            if (!SettingsNavigator.openTether(this)) message("无法打开系统入口", "请手动进入系统设置中的移动网络或个人热点/网络共享。菜单名称因设备而异。");
        });
        addButton(preparation, "无障碍授权说明", false, v -> explainAccessibility());

        LinearLayout bootCard = card(page);
        rowTitle(bootCard, "开机自动守护", "可选 · 默认关闭", teal);
        bootValue = text("正在读取开机设置…", 15, ink, false);
        bootValue.setPadding(0, dp(8), 0, dp(8));
        bootCard.addView(bootValue);
        bootToggle = addButton(bootCard, "开启开机自动守护", false, v -> configureBoot());
        TextView bootHint = text("开启后从下次重启生效，不会立即启用守护。无障碍需先手动授权；开机等待连接最多120秒。开机后共享页未确认且尚未点击时，最多3次核对，失败后至少等10秒；点击后结果未知仍暂停。点击“停止守护”也会关闭此选项。", 14, muted, false);
        bootHint.setPadding(0, dp(10), 0, 0);
        bootCard.addView(bootHint);

        LinearLayout overlayCard = card(page);
        rowTitle(overlayCard, "后台打开页面验证", "可选 · 特殊权限", teal);
        overlayValue = text("正在读取显示权限…", 15, ink, false);
        overlayValue.setPadding(0, dp(8), 0, dp(8));
        overlayCard.addView(overlayValue);
        addButton(overlayCard, "查看显示在其他应用上层授权", false, v -> explainOverlayAccess());
        TextView overlayHint = text("此权限允许应用在其他应用上层显示内容，授权范围较广。本测试版不创建悬浮窗，仅验证授权是否有助于后台打开亮屏页面，华为本机效果尚未验证。授权返回不会启用守护或绕过锁屏；不授权也可继续使用原功能。撤销此权限不会停止已运行的守护，请使用“停止守护”。", 14, muted, false);
        overlayHint.setPadding(0, dp(10), 0, 0);
        overlayCard.addView(overlayHint);

        LinearLayout intervalCard = card(page);
        rowTitle(intervalCard, "备用检查频率", "系统事件优先", teal);
        RadioGroup polls = new RadioGroup(this);
        polls.setOrientation(RadioGroup.VERTICAL);
        for (int seconds : new int[] {30, 60, 120}) {
            RadioButton choice = new RadioButton(this);
            choice.setId(seconds == 30 ? R.id.interval30 : seconds == 120 ? R.id.interval120 : R.id.interval60);
            choice.setText(seconds + " 秒");
            choice.setTextSize(15);
            choice.setMinHeight(dp(48));
            polls.addView(choice, new RadioGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        polls.check(pollSeconds == 30 ? R.id.interval30 : pollSeconds == 120 ? R.id.interval120 : R.id.interval60);
        polls.setOnCheckedChangeListener((group, selected) -> {
            pollSeconds = selected == R.id.interval30 ? 30 : selected == R.id.interval120 ? 120 : 60;
            if (!BootPreferences.setPollSeconds(this, pollSeconds))
                store.report("检查间隔保存失败，请检查应用存储；下次启动设置可能未保存", "poll_setting_save_failed");
        });
        intervalCard.addView(polls);
        intervalCard.addView(text("下次手动启动或开机生效；当前运行间隔以守护卡为准。", 14, muted, false));

        addDisclosure(page, "高级验证与日志", advancedContent());
        LinearLayout diagnosticContent = new LinearLayout(this);
        diagnosticContent.setOrientation(LinearLayout.VERTICAL);
        details = text("详细状态尚未刷新。", 14, ink, false);
        details.setTextIsSelectable(true);
        diagnosticContent.addView(details);
        addButton(diagnosticContent, "刷新诊断", false, v -> { store.refresh(); renderStatus(); });
        addDisclosure(page, "详细诊断", diagnosticContent);
        renderStatus();
    }

    private LinearLayout advancedContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        verify = addButton(box, "验证一次自动开启", true, v -> confirmVerification());
        cancelVerification = addButton(box, "取消单次验证", false, v -> {
            VerificationAttempt current = store.attempts.current();
            if (current != null && current.owner == VerificationAttempt.Owner.MANUAL) store.cancel("用户取消单次验证");
            renderStatus();
        });
        addButton(box, "查看本地诊断日志", false, v -> showLog());
        addButton(box, "手动导出日志", false, v -> exportLog());
        addButton(box, "清空本地日志", false, v -> new AlertDialog.Builder(this).setTitle("清空诊断日志？")
                .setMessage("仅清空本地小日志；当前状态诊断快照继续保留，不包含屏幕全文。")
                .setNegativeButton("返回", null).setPositiveButton("清空", (dialog, which) -> {
                    store.log.clear(); store.changed(); renderStatus();
                }).show());
        return box;
    }

    private void addDisclosure(LinearLayout parent, String label, View content) {
        LinearLayout section = card(parent);
        addButton(section, label + "  ＋", false, v -> {
            boolean opening = content.getVisibility() != View.VISIBLE;
            content.setVisibility(opening ? View.VISIBLE : View.GONE);
            ((Button) v).setText(label + (opening ? "  －" : "  ＋"));
        });
        content.setVisibility(View.GONE);
        section.addView(content);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackground(shape(surface, border, dp(16)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(box, params);
        return box;
    }

    private void rowTitle(LinearLayout parent, String label, String badge, int badgeColor) {
        LinearLayout row = new LinearLayout(this);
        boolean vertical = getResources().getConfiguration().fontScale >= 1.3f
                || getResources().getConfiguration().screenWidthDp < 360;
        row.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(vertical ? android.view.Gravity.START : android.view.Gravity.CENTER_VERTICAL);
        TextView heading = text(label, 18, ink, true);
        if (vertical) row.addView(heading);
        else row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView tag = text(badge, 12, badgeColor, true);
        if (vertical) {
            tag.setPadding(0, dp(4), 0, 0);
            row.addView(tag);
        } else row.addView(tag);
        parent.addView(row);
    }

    private TextView addState(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView name = text(label, 15, muted, false);
        row.addView(name);
        TextView state = text(value, 15, ink, true);
        state.setPadding(0, dp(4), 0, 0);
        row.addView(state);
        parent.addView(row);
        View divider = new View(this);
        divider.setBackgroundColor(border);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        return state;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button addButton(LinearLayout parent, String label, boolean primary, View.OnClickListener action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setTextColor(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {android.R.attr.state_enabled}},
                new int[] {muted, primary ? Color.WHITE : navy}));
        button.setBackground(buttonBackground(primary ? navy : getColor(R.color.action_secondary),
                primary ? getColor(R.color.action_primary_pressed) : getColor(R.color.action_secondary_pressed),
                getColor(R.color.action_disabled)));
        button.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        parent.addView(button, params);
        return button;
    }

    private StateListDrawable buttonBackground(int normal, int pressed, int disabled) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled}, shape(disabled, disabled, dp(12)));
        states.addState(new int[] {android.R.attr.state_pressed}, shape(pressed, pressed, dp(12)));
        states.addState(new int[] {android.R.attr.state_focused}, shape(pressed, teal, dp(12)));
        states.addState(new int[] {}, shape(normal, normal, dp(12)));
        return states;
    }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void setTextIfChanged(TextView view, CharSequence value) {
        if (view != null && !value.equals(view.getText())) view.setText(value);
    }

    private void setTextColorIfChanged(TextView view, int color) {
        if (view != null && view.getCurrentTextColor() != color) view.setTextColor(color);
    }

    private void renderStatus() {
        if (guardValue == null) return;
        boolean fresh = store.fresh(store.usbAt);
        String usb = !fresh || store.connected == ProbeStore.Truth.UNKNOWN ? "未知"
                : store.connected == ProbeStore.Truth.YES ? "已连接" : "已断开";
        Boolean currentSwitch = store.currentSwitchChecked();
        String checked = currentSwitch == null ? "未知（过期、不可读或未观测）" : (currentSwitch ? "开启（最近可靠观测）" : "关闭（最近可靠观测）");
        boolean startPending = GuardService.hasPendingStart();
        String guard = store.guardEnabled ? store.guardStatus : startPending ? "启动请求已排队，可随时停止" : "守护未启用";
        String tether = store.usbInterfaces();
        String guardSummary = guard + "\n本轮故障尝试：" + store.guardAttempts + "/3"
                + "\n当前备用检查间隔：" + store.guardPollSeconds + " 秒（系统事件优先）";
        setTextIfChanged(guardValue, guard);
        setTextIfChanged(guardHint, guardSummary + (store.guardEnabled
                ? "\n持续通知可停止守护。"
                : store.policy.phase() != OneShotPolicy.Phase.IDLE ? "\n单次验证进行中，暂不能启用守护。"
                : "\nUSB 重连后按安全条件尝试开启共享；启用后显示持续通知，可随时停止。"));
        setTextIfChanged(usbValue, usb + (fresh ? "" : " · 未观测或已过期"));
        setTextIfChanged(tetherValue, tether);
        setTextIfChanged(switchValue, checked);
        setTextColorIfChanged(usbValue, !fresh || store.connected == ProbeStore.Truth.UNKNOWN ? muted
                : store.connected == ProbeStore.Truth.YES ? getColor(R.color.state_success) : getColor(R.color.state_error));
        setTextColorIfChanged(tetherValue, tether.startsWith("USB共享接口已观察到") ? getColor(R.color.state_success) : muted);
        setTextColorIfChanged(switchValue, currentSwitch == null ? muted
                : currentSwitch ? getColor(R.color.state_success) : getColor(R.color.state_error));
        setTextColorIfChanged(guardValue, store.guardEnabled ? getColor(R.color.state_success) : ink);
        setTextIfChanged(resultValue, store.result);
        boolean bootEnabled = BootPreferences.enabled(this);
        setTextIfChanged(bootValue, (bootEnabled ? "已开启 · 下次开机尝试恢复守护" : "已关闭 · 重启后需手动启用守护")
                + "\n最近开机处理（历史）：" + BootPreferences.lastResult(this));
        setTextIfChanged(bootToggle, bootEnabled ? "关闭开机自动守护" : "开启开机自动守护");
        OverlayAccess.Status overlay = OverlayAccess.status(this);
        setTextIfChanged(overlayValue, "显示在其他应用上层：" + (overlay == OverlayAccess.Status.GRANTED ? "已授权"
                : overlay == OverlayAccess.Status.DENIED ? "未授权" : "未知（系统状态暂不可读）"));
        boolean notificationsOn = GuardService.notificationsVisible(this);
        boolean foregroundPermission = checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        setTextIfChanged(prepareValue, (store.service == null ? "无障碍服务未连接。" : "无障碍服务已连接。")
                + (notificationsOn ? " 通知已开启。" : " 通知尚未开启，请到系统设置中手动允许。")
                + (foregroundPermission ? " 前台服务权限已具备。" : " 前台服务权限尚未具备。")
                + " 首次安装守护默认关闭；有锁屏时等待解锁。可在下方查看配置建议。");
        PageReadyEvidence.Facts page = store.pageReady.facts();
        setTextIfChanged(details, "设备：" + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\nUSB数据连接：" + usb + (fresh ? "" : "（未观测或已过期）")
                + "\n来源：" + store.usbSource + "\n观测：" + time(store.usbWall)
                + "\nUSB共享接口：" + tether
                + "\n来源：" + store.tetherSource + "\n观测：" + time(store.tetherWall) + (store.fresh(store.tetherAt) ? "" : "（未观测或已过期）")
                + "\n系统开关：" + checked + " / " + time(store.switchWall)
                + "\n状态规则：" + store.switchAdapter
                + (Boolean.FALSE.equals(store.switchCheckable) ? "\ncheckable=false；仅允许本机精确匹配适配，不一致时显示未知" : "")
                + "\n历史开关证据：" + (store.switchChecked == null ? "未知" : store.switchChecked ? "曾观测开启" : "曾观测关闭")
                + "\n无障碍服务：" + (store.service == null ? "未连接" : "已连接（默认只观察）")
                + "\n亮屏且未锁定：" + (ProbeAccessibilityService.unlocked(this) ? "是" : "否")
                + "\n验证阶段：" + store.policy.phase().name()
                + "\n共享检查来源：" + store.guardSource
                + "\n最近尝试页面线索（历史）：" + (page.lastPage == null ? "未采样" : page.lastPage.name())
                + "\n页面采样距开机：" + page.sampleAt + "毫秒；当前尝试：" + (page.active ? "是" : "否")
                + "\n该尝试曾确认共享页：" + (page.everConfirmed ? "是" : "否")
                + "；可能提交过点击：" + (page.possibleClick ? "是" : "否")
                + "\n最近处理：" + store.result
                + "\n电脑联网：未验证"
                + "\n诊断快照：" + (store.snapshotWritten ? "已写入应用内部目录" : "写入失败")
                + "\n诊断日志：" + (store.log.written ? "本地记录正常" : "写入失败；仍可查看进程内记录")
                + "\n\n广播协议普通APK可见性待验证；观测120秒后过期。rndis/ncm标志不单独作为共享开启证据。");
        if (verify != null) verify.setEnabled(!startPending && !store.guardEnabled && store.service != null && store.reliableConnected()
                && ProbeAccessibilityService.unlocked(this) && store.policy.phase() == OneShotPolicy.Phase.IDLE);
        if (startGuard != null) {
            startGuard.setVisibility(store.guardEnabled || startPending ? View.GONE : View.VISIBLE);
            startGuard.setEnabled(!startPending && store.policy.phase() == OneShotPolicy.Phase.IDLE);
        }
        if (stopGuard != null) {
            stopGuard.setVisibility(store.guardEnabled || startPending ? View.VISIBLE : View.GONE);
            stopGuard.setEnabled(store.guardEnabled || startPending);
        }
        if (cancelVerification != null) cancelVerification.setEnabled(!store.guardEnabled && store.policy.phase() != OneShotPolicy.Phase.IDLE);
    }

    private String time(long millis) {
        return millis == 0 ? "未观察" : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(millis));
    }

    private void configureBoot() {
        if (BootPreferences.enabled(this)) {
            new AlertDialog.Builder(this).setTitle("关闭开机自动守护？")
                    .setMessage("将取消下次开机自动启动和当前尚未完成的开机等待；已经运行的正常守护继续运行。如需全部停止，请点击“停止守护”。")
                    .setNegativeButton("返回", null).setPositiveButton("关闭开机自动守护", (dialog, which) -> {
                        if (!BootPreferences.setEnabled(this, false))
                            message("设置未保存", "开机设置写入失败，请检查应用存储。当前已排队的自动启动会被撤销。");
                        renderStatus();
                    }).show();
            return;
        }
        if (!GuardService.supported() || !GuardService.notificationsVisible(this) || store.service == null) {
            message("暂不能开启", "请先在已适配的本机开启通知，并确认“USB共享辅助服务”已连接。应用不会代为授予无障碍权限。");
            return;
        }
        new AlertDialog.Builder(this).setTitle("开启开机自动守护？")
                .setMessage("从下次手机重启完成起，尝试启动带常驻通知的守护；当前不会立即启动。仅接收系统开机完成事件，不能保证首次解锁前可运行。\n\n等待已授权的无障碍服务连接最多120秒；未连接或权限失效时不操作共享，需查看结果并人工处理。不会自动开启无障碍授权，也不会绕过锁屏。\n\n停止守护会同时关闭本选项；划走任务或进程被回收后不自动复活。华为系统可能限制开机启动，1.1尚待本机真实重启测试。")
                .setNegativeButton("返回", null).setPositiveButton("同意，开启开机守护", (dialog, which) -> {
                    // Recheck after the dialog: permission/service state may have changed.
                    if (!resumed || !GuardService.supported() || !GuardService.notificationsVisible(this) || store.service == null) {
                        message("未开启", "授权或服务状态已变化，请检查后重试。");
                        return;
                    }
                    if (!BootPreferences.setEnabled(this, true))
                        message("未开启", "开机设置保存失败，请检查应用存储后重试。");
                    renderStatus();
                }).show();
    }

    private void explainOverlayAccess() {
        new AlertDialog.Builder(this).setTitle("可选：显示在其他应用上层")
                .setMessage("这是一项范围较广的特殊权限，允许应用在其他应用上层显示内容。本测试版不创建悬浮窗，仅用于验证系统是否因此允许后台打开亮屏页面；华为实际效果未验证，也不保证共享恢复成功。\n\n请由您在系统中决定是否授权。进入后可能显示应用列表，请找到“USB共享助手”。返回应用会刷新状态，不会自动启用守护、修改开机选项或绕过锁屏。\n\n以后撤销此权限不会停止已有守护，需要停止时请使用应用或通知中的“停止守护”。")
                .setNegativeButton("返回", null).setPositiveButton("前往系统授权页", (dialog, which) -> {
                    if (!resumed) return;
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException unavailable) {
                        message("系统入口不可用", "请手动打开系统设置 → 应用 → 特殊访问权限 → 显示在其他应用上层，找到“USB共享助手”。菜单名称可能因设备而异；由您决定是否授权。");
                    }
                }).show();
    }

    private void showSetupNotes() {
        new AlertDialog.Builder(this).setTitle("首次配置建议")
                .setMessage("请手动将锁屏方式设置为“无”。如果本机开发者选项提供“不使用锁屏/跳过锁屏”，请启用；并关闭充电时自动显示的画面或动画，具体名称以本机为准，以免遮挡共享设置。\n\n取消密码但保留滑动锁屏，仍可能影响息屏恢复。应用不会自动修改这些系统选项。USB 调试不是应用运行的必需条件。\n\n本机 0.7 用户回报六轮息屏重连成功；不保证系统永久保活，锁屏时等待解锁。")
                .setPositiveButton("知道了", null).show();
    }

    private void explainAccessibility() {
        new AlertDialog.Builder(this).setTitle("无障碍能力与限制")
                .setMessage("服务“USB共享辅助服务”（旧版名称“USB共享单次验证”）仅接收系统设置包事件并读取节点，识别USB共享行及开关。默认仅观察；您发起单次验证或手动启用守护后，在USB可靠连接、亮屏未锁定时，才可能点击唯一确认且关闭的USB开关。每次尝试最多点击一次，守护每次故障最多3次。\n\n不自动授权、不绕过锁屏、不执行手势、不截屏、不记录屏幕全文。可随时停止守护或在系统中关闭服务。\n\n请在无障碍设置中找到“USB共享辅助服务”并自行决定是否开启；本应用不会操作授权弹窗。")
                .setNegativeButton("返回", null).setPositiveButton("前往系统无障碍页", (dialog, which) -> {
                    if (!SettingsNavigator.openAccessibility(this)) message("系统入口不可用", "请手动打开设置 → 辅助功能 → 无障碍，找到USB共享辅助服务。");
                }).show();
    }

    private void confirmGuard() {
        new AlertDialog.Builder(this).setTitle("启用辅助守护？")
                .setMessage("息屏且未设置安全锁时，将尝试显示普通页面短暂亮屏，再确认共享；不会操作锁屏。有安全锁时等待您自行解锁。\n\n会显示持续通知，可从通知或此处停止。系统事件优先，备用检查间隔为" + pollSeconds + "秒。USB重新连接会自动检查，必要时将系统共享设置带到前台，打断当前应用；不需要每次手动验证。\n\n亮屏最多等待10秒，每轮最多3次。状态未知、页面不可读或亮屏失败时，本轮暂停；USB断开至少3秒接回后自动再检查，失败后仍至少冷却10秒。新鲜共享接口可恢复观察，不代表本次开关最终确认。权限或服务中断需停止并重新启用守护。\n\n本机0.7用户回报六轮息屏重连成功；不保证系统永久保活，锁屏时等待解锁。手动关闭共享前请先停止守护。停止守护也会关闭开机自动守护。移除任务或进程被回收后不自动复活；仅已开启开机自动守护时，下次重启才会尝试恢复。Windows联网不作为重试依据。")
                .setNegativeButton("返回", null).setPositiveButton("启用守护", (dialog, which) -> {
                    if (!GuardService.startFromActivity(this, pollSeconds, resumed)) message("守护未启动", store.result);
                }).show();
    }

    private void confirmVerification() {
        new AlertDialog.Builder(this).setTitle("发起一次验证？")
                .setMessage("将打开系统共享设置，在30秒内只对唯一确认、未开启的USB共享开关提交一次点击。已开启时不点击；节点不明确时拒绝。\n\n息屏、锁定、USB断开/状态失效、服务中断或用户取消立即撤销。点击后最多10秒只读回查，不操作确认弹窗，不自动重试。电脑联网需独立验证。")
                .setNegativeButton("返回", null).setPositiveButton("验证一次", (dialog, which) -> {
                    if (store.service == null) { message("无法验证", "无障碍服务尚未连接。请先阅读授权说明并自行决定是否开启。"); return; }
                    store.service.startVerification(); renderStatus();
                }).show();
    }

    private void message(String title, String body) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("知道了", null).show();
    }

    private void showLog() {
        TextView text = text("", 14, ink, false);
        text.setTextIsSelectable(true);
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        String content = store.log.text(); text.setText(content.isEmpty() ? "暂无诊断日志" : content);
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("本地诊断日志（最多300行 / 256KiB）").setView(scroll).setPositiveButton("关闭", null).show();
    }

    private void exportLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "usb-probe-diagnostic.txt");
        try { startActivityForResult(intent, EXPORT_LOG); }
        catch (RuntimeException unavailable) { message("无法导出", "系统未提供文档保存入口；日志仍保留在应用内部。"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_LOG || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData(); if (uri == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new java.io.IOException("No output stream");
            out.write(store.log.text().getBytes(StandardCharsets.UTF_8));
            message("日志已导出", "已写入您选择的文档位置。");
        } catch (Exception failed) { message("导出失败", "文档不可写或系统拒绝访问；本地日志仍保留。"); }
    }

    @Override protected void onStart() {
        super.onStart(); store.listeners.add(changed); handler.post(render);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; renderStatus(); }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onStop() {
        handler.removeCallbacks(render); store.listeners.remove(changed); super.onStop();
    }
}
