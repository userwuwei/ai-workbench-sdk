package com.cscjapp.aiworkbench.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.request.RequestOptions;
import com.chad.library.adapter.base.BaseMultiItemQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.cscjapp.aiworkbench.android.R;
import com.cscjapp.aiworkbench.android.widget.AnimatedNumberView;
import com.cscjapp.aiworkbench.android.widget.WorkbenchWaitingAuraView;

import java.util.ArrayList;
import java.util.List;

final class WorkbenchItemAdapter extends BaseMultiItemQuickAdapter<WorkbenchUiItem, BaseViewHolder> {

    public static final int TYPE_PLAN = 1;
    public static final int TYPE_THOUGHT = 2;
    public static final int TYPE_SUMMARY = 3;
    public static final int TYPE_REASON = 4;
    public static final int TYPE_EDIT_NOTICE = 5;
    public static final int TYPE_USER_DEMAND = 6;
    public static final int TYPE_BROWSER_TEST = 7;
    private static final int THOUGHT_CONTENT_COLLAPSED_MAX_LINES = 4;
    private static final int SUMMARY_CONTENT_COLLAPSED_MAX_LINES = 3;
    private static final int SUMMARY_CONTENT_COLLAPSED_MIN_CHARS = 140;
    private static final int STREAMING_COLLAPSED_PREVIEW_MAX_CHARS = 4096;
    private static final int DIFF_COLLAPSED_MAX_LINES = 8;

    private OnThoughtItemClickListener onThoughtItemClickListener;
    private OnReasonItemClickListener onReasonItemClickListener;
    private OnSummaryActionClickListener onSummaryActionClickListener;
    private OnItemLongClickListener onItemLongClickListener;
    private OnPresentationStateChangedListener onPresentationStateChangedListener;
    private boolean codeAreaLocked = false;
    private String userAvatarUrl = "";

    WorkbenchItemAdapter(@Nullable List<WorkbenchUiItem> data) {
        super(data);
        addItemType(TYPE_PLAN, R.layout.aiw_item_plan);
        addItemType(TYPE_THOUGHT, R.layout.aiw_item_thought);
        addItemType(TYPE_SUMMARY, R.layout.aiw_item_summary);
        addItemType(TYPE_REASON, R.layout.aiw_item_summary);
        addItemType(TYPE_EDIT_NOTICE, R.layout.aiw_item_summary);
        addItemType(TYPE_USER_DEMAND, R.layout.aiw_item_user_demand);
        addItemType(TYPE_BROWSER_TEST, R.layout.aiw_item_browser_test);
    }

    @Override
    protected void convert(BaseViewHolder helper, WorkbenchUiItem item) {
        int type = helper.getItemViewType();
        if (type == TYPE_PLAN) {
            bindPlan(helper, item);
            return;
        }
        if (type == TYPE_THOUGHT) {
            bindThought(helper, item);
            helper.itemView.setOnClickListener(v -> {
                if (onThoughtItemClickListener != null) {
                    onThoughtItemClickListener.onThoughtItemClick(item, helper.getLayoutPosition());
                }
            });
            bindItemLongClick(helper, item);
            return;
        }
        if (type == TYPE_REASON) {
            bindReason(helper, item);
            bindSummaryAction(helper, item);
            helper.itemView.setOnClickListener(v -> {
                if (onReasonItemClickListener != null) {
                    onReasonItemClickListener.onReasonItemClick(item, helper.getLayoutPosition());
                }
            });
            bindItemLongClick(helper, item);
            return;
        }
        if (type == TYPE_EDIT_NOTICE) {
            bindEditNotice(helper, item);
            bindItemLongClick(helper, item);
            return;
        }
        if (type == TYPE_USER_DEMAND) {
            helper.setText(R.id.aiw_tvSummaryTitle, item.title);
            helper.setText(R.id.aiw_tvSummaryContent, item.content);
            helper.setTextColor(R.id.aiw_tvSummaryTitle, Color.parseColor("#93C5FD"));
            helper.setTextColor(R.id.aiw_tvSummaryContent, Color.parseColor("#DBEAFE"));

            ImageView imgHead = helper.getView(R.id.aiw_image_head);
            imgHead.setImageDrawable(null);
            if (!TextUtils.isEmpty(userAvatarUrl)) {
                Glide.with(mContext)
                        .load(userAvatarUrl)
                        .apply(RequestOptions.bitmapTransform(new CircleCrop()))
                        .into(imgHead);
            }
            bindItemLongClick(helper, item);
            return;
        }
        if (type == TYPE_BROWSER_TEST) {
            bindBrowserTest(helper, item);
            bindItemLongClick(helper, item);
            return;
        }
        helper.setText(R.id.aiw_tvSummaryTitle, displaySummaryTitle(item.title));
        bindSummaryContent(helper, item, false);
        applySummaryVisualState(helper, item,
                Color.parseColor("#F0F6FF"),
                Color.parseColor("#D6E2F7"));
        hideSummaryExtras(helper);
        bindWaitingEffect(helper, item);
        bindSummaryAction(helper, item);
        bindItemLongClick(helper, item);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder,
                                 int position,
                                 @NonNull List<Object> payloads) {
        int mask = WorkbenchStreamPayload.NONE;
        for (Object payload : payloads) {
            if (payload instanceof WorkbenchStreamPayload) {
                mask |= ((WorkbenchStreamPayload) payload).mask;
            }
        }
        if (mask == WorkbenchStreamPayload.NONE
                || position < 0
                || position >= getData().size()) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        WorkbenchUiItem item = getData().get(position);
        if (item.type == TYPE_THOUGHT) {
            bindThoughtPayload(holder, item, mask);
            return;
        }
        if (item.type == TYPE_REASON) {
            bindReasonPayload(holder, item, mask);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onViewRecycled(@NonNull BaseViewHolder holder) {
        int type = holder.getItemViewType();
        if (type == TYPE_REASON || type == TYPE_SUMMARY || type == TYPE_EDIT_NOTICE) {
            AnimatedNumberView counter = holder.getView(R.id.aiw_anvSummaryCounter);
            counter.cancelAnimations();
            WorkbenchWaitingAuraView aura = holder.getView(R.id.aiw_vSummaryWaitingAura);
            aura.setActive(false);
            aura.resetAnimationPhase();
        }
        super.onViewRecycled(holder);
    }

    private void bindPlan(BaseViewHolder helper, WorkbenchUiItem item) {
        TextView tvProgress = helper.getView(R.id.aiw_tvProgress);
        TextView tvPlanCount = helper.getView(R.id.aiw_tvPlanCount);
        TextView tvPlanGoal = helper.getView(R.id.aiw_tvPlanGoal);
        TextView tvPlanFooter = helper.getView(R.id.aiw_tvPlanFooter);
        TextView tvPlanExpand = helper.getView(R.id.aiw_tvPlanExpand);
        ProgressBar pbPlanProgress = helper.getView(R.id.aiw_pbPlanProgress);
        LinearLayout qualityContainer = helper.getView(R.id.aiw_llPlanQuality);
        LinearLayout stepsContainer = helper.getView(R.id.aiw_llPlanSteps);

        String goal = "";
        String quality = "";
        String qualityFocus = "";
        String qualityMode = "";
        String qualityStatus = "";
        String designStatus = "";
        String polishStatus = "";
        ArrayList<String> planSteps = new ArrayList<>();
        if (item.steps != null) {
            for (String line : item.steps) {
                if (TextUtils.isEmpty(line)) {
                    continue;
                }
                String trimLine = line.trim();
                if (trimLine.startsWith("目标：")) {
                    goal = trimLine;
                } else if (trimLine.startsWith("质量标准：")) {
                    quality = trimLine.replace("质量标准：", "");
                } else if (trimLine.startsWith("质检重点：")) {
                    qualityFocus = trimLine.replace("质检重点：", "").replace("·", "/");
                } else if (trimLine.startsWith("质量模式：")) {
                    qualityMode = trimLine.replace("质量模式：", "");
                } else if (trimLine.startsWith("质检状态：")) {
                    qualityStatus = trimLine.replace("质检状态：", "质检");
                } else if (trimLine.startsWith("设计规格：")) {
                    designStatus = trimLine.replace("设计规格：", "设计");
                } else if (trimLine.startsWith("打磨状态：")) {
                    polishStatus = trimLine.replace("打磨状态：", "打磨");
                } else {
                    planSteps.add(trimLine);
                }
            }
        }

        tvProgress.setText(formatPlanCurrentStep(item.title));
        bindPlanProgress(tvPlanCount, pbPlanProgress, planSteps);
        bindPlanGoal(tvPlanGoal, item.detailExpanded ? goal : "");
        String compactQuality = mergePlanQuality(mergePlanQuality(mergePlanQuality(qualityMode, qualityStatus), designStatus), polishStatus);
        bindPlanQuality(qualityContainer, item.detailExpanded
                ? mergePlanQuality(compactQuality, mergePlanQuality(qualityFocus, quality))
                : compactQuality);

        stepsContainer.removeAllViews();
        if (planSteps.isEmpty()) {
            helper.setGone(R.id.aiw_llPlanFooter, false);
            return;
        }
        Context context = stepsContainer.getContext();
        ArrayList<String> visibleSteps = item.detailExpanded ? new ArrayList<>(planSteps) : new ArrayList<>();
        stepsContainer.setVisibility(item.detailExpanded ? View.VISIBLE : View.GONE);
        if (item.detailExpanded) {
            for (int i = 0; i < visibleSteps.size(); i++) {
                String step = visibleSteps.get(i);
                if (TextUtils.isEmpty(step)) {
                    continue;
                }
                LinearLayout stepView = createPlanStepRow(context, step);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                params.topMargin = dp(mContext, i == 0 ? 0 : 7);
                stepView.setLayoutParams(params);
                stepsContainer.addView(stepView);
            }
        }
        int hiddenCount = planSteps.size() - visibleSteps.size();
        boolean footerVisible = !planSteps.isEmpty();
        helper.setGone(R.id.aiw_llPlanFooter, footerVisible);
        tvPlanFooter.setText(item.detailExpanded
                ? "已展开全部步骤，完整计划仍会回传给模型"
                : hiddenCount > 0 ? "完整计划已折叠，不占用主流程空间" : "");
        tvPlanExpand.setText(item.detailExpanded ? "收起" : "展开");
        tvPlanExpand.setOnClickListener(footerVisible ? v -> {
            item.detailExpanded = !item.detailExpanded;
            int position = helper.getLayoutPosition();
            if (position >= 0) {
                notifyItemChanged(position);
            }
            notifyPresentationStateChanged(item);
        } : null);
    }

    private String formatPlanCurrentStep(String title) {
        if (TextUtils.isEmpty(title)) {
            return "当前 · 规划中";
        }
        String value = title.trim();
        int currentIndex = value.indexOf("当前：");
        if (currentIndex >= 0) {
            String current = value.substring(currentIndex + "当前：".length()).trim();
            return TextUtils.isEmpty(current) ? "当前 · 推进中" : "当前 · " + current;
        }
        return value.replace("任务计划 · ", "");
    }

    private void bindPlanProgress(TextView tvPlanCount, ProgressBar progressBar, List<String> planSteps) {
        int total = 0;
        int done = 0;
        if (planSteps != null) {
            for (String step : planSteps) {
                if (TextUtils.isEmpty(step)) {
                    continue;
                }
                total++;
                if (step.trim().startsWith("✓")) {
                    done++;
                }
            }
        }
        tvPlanCount.setText(done + " / " + total);
        progressBar.setMax(100);
        progressBar.setProgress(total <= 0 ? 0 : Math.round(done * 100f / total));
        progressBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#74EBD5")));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#28364F")));
    }

    private void bindPlanGoal(TextView tvPlanGoal, String goal) {
        if (TextUtils.isEmpty(goal)) {
            tvPlanGoal.setVisibility(View.GONE);
            tvPlanGoal.setText("");
            return;
        }
        tvPlanGoal.setVisibility(View.VISIBLE);
        tvPlanGoal.setText(goal);
    }

    private void bindPlanQuality(LinearLayout qualityContainer, String quality) {
        qualityContainer.removeAllViews();
        if (TextUtils.isEmpty(quality)) {
            qualityContainer.setVisibility(View.GONE);
            return;
        }
        qualityContainer.setVisibility(View.VISIBLE);
        Context context = qualityContainer.getContext();
        String[] chips = quality.split("/");
        int added = 0;
        for (String chip : chips) {
            String label = chip == null ? "" : chip.trim();
            if (TextUtils.isEmpty(label)) {
                continue;
            }
            TextView chipView = new TextView(context);
            chipView.setSingleLine(true);
            chipView.setEllipsize(TextUtils.TruncateAt.END);
            chipView.setIncludeFontPadding(false);
            chipView.setText(label);
            chipView.setTextColor(Color.parseColor("#B9C7D9"));
            chipView.setTextSize(11);
            chipView.setPadding(dp(mContext, 8), dp(mContext, 4),
                    dp(mContext, 8), dp(mContext, 4));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#101827"));
            bg.setStroke(dp(mContext, 1), Color.parseColor("#2A3A58"));
            bg.setCornerRadius(dp(mContext, 999));
            chipView.setBackground(bg);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (added > 0) {
                params.leftMargin = dp(mContext, 6);
            }
            qualityContainer.addView(chipView, params);
            added++;
            if (added >= 4) {
                break;
            }
        }
        if (added == 0) {
            qualityContainer.setVisibility(View.GONE);
        }
    }

    private String mergePlanQuality(String qualityFocus, String quality) {
        if (TextUtils.isEmpty(qualityFocus)) {
            return quality;
        }
        if (TextUtils.isEmpty(quality)) {
            return qualityFocus;
        }
        return qualityFocus + " / " + quality;
    }

    private LinearLayout createPlanStepRow(Context context, String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(mContext, 24));

        boolean done = line.startsWith("✓");
        boolean running = line.startsWith("▶");
        boolean blocked = line.startsWith("⚠");
        boolean skipped = line.startsWith("—");
        boolean pending = line.startsWith("○");
        String title = (done || running || blocked || skipped || pending) && line.length() > 1
                ? line.substring(1).trim()
                : line;

        TextView markerView = new TextView(context);
        markerView.setIncludeFontPadding(false);
        markerView.setText(done ? "✓" : running ? "▶" : blocked ? "!" : skipped ? "—" : "○");
        markerView.setTextSize(13);
        markerView.setTypeface(Typeface.DEFAULT_BOLD);
        markerView.setTextColor(done ? Color.parseColor("#55D98B")
                : running ? Color.parseColor("#74EBD5")
                : blocked ? Color.parseColor("#FDA4AF")
                : skipped ? Color.parseColor("#7C8DA6")
                : Color.parseColor("#63748C"));
        row.addView(markerView, new LinearLayout.LayoutParams(
                dp(mContext, 18),
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView stepView = new TextView(context);
        bindPlanLine(stepView, title, done, running, blocked);
        row.addView(stepView, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        ));
        return row;
    }

    private void bindPlanLine(TextView stepView, String line, boolean done, boolean running, boolean blocked) {
        String displayLine = line == null ? "" : line.trim();
        stepView.setSingleLine(true);
        stepView.setEllipsize(TextUtils.TruncateAt.END);
        stepView.setTextSize(13);
        stepView.setIncludeFontPadding(false);
        stepView.setMinHeight(dp(mContext, 24));
        stepView.setGravity(android.view.Gravity.CENTER_VERTICAL);
        stepView.setBackground(null);
        stepView.setTypeface(Typeface.DEFAULT);
        stepView.setPadding(0, 0, 0, 0);

        stepView.setText(displayLine);
        if (done) {
            stepView.setTextColor(Color.parseColor("#BFEBD0"));
        } else if (running) {
            stepView.setTextColor(Color.parseColor("#F8FAFC"));
            stepView.setTypeface(Typeface.DEFAULT_BOLD);
        } else if (blocked) {
            stepView.setTextColor(Color.parseColor("#FDA4AF"));
        } else {
            stepView.setTextColor(Color.parseColor("#93A4B8"));
        }
    }


    private void bindThought(BaseViewHolder helper, WorkbenchUiItem item) {
        TextView tvThoughtTitle = helper.getView(R.id.aiw_tvThoughtTitle);
        View thoughtBodyCard = helper.getView(R.id.aiw_cardThoughtBody);
        boolean trivialCompletedThought = isTrivialCompletedThought(item);
        String resolvedCodeBlock = item.resolvedCodeBlock();
        boolean hasCode = !TextUtils.isEmpty(resolvedCodeBlock);
        boolean hideTrivialBody = trivialCompletedThought && (!hasCode || !item.codeExpanded);
        tvThoughtTitle.setText(trivialCompletedThought
                ? displayCompletedThoughtTitle(item.title)
                : item.thoughtContentExpanded ? item.title + " · 收起" : item.title + " · 展开");
        tvThoughtTitle.setTextSize(16);
        tvThoughtTitle.setTextColor(Color.parseColor(trivialCompletedThought ? "#7F8EA8" : "#AAB9D4"));
        thoughtBodyCard.setVisibility(hideTrivialBody ? View.GONE : View.VISIBLE);
        TextView tvCodeToggle = helper.getView(R.id.aiw_tvThoughtCodeToggle);
        tvCodeToggle.setVisibility(hasCode ? View.VISIBLE : View.GONE);
        tvCodeToggle.setText(item.codeExpanded ? "收起深度思考" : "查看深度思考");
        tvCodeToggle.setOnClickListener(v -> {
            item.codeExpanded = !item.codeExpanded;
            int position = helper.getLayoutPosition();
            if (position >= 0) {
                notifyItemChanged(position);
            }
            notifyPresentationStateChanged(item);
        });
        if (hideTrivialBody) {
            return;
        }
        TextView tvThoughtContent = helper.getView(R.id.aiw_tvThoughtContent);
        tvThoughtContent.setText(sanitizeWorkbenchText(item.content));
        tvThoughtContent.setVisibility(trivialCompletedThought ? View.GONE : View.VISIBLE);
        if (item.thoughtContentExpanded) {
            tvThoughtContent.setMaxLines(Integer.MAX_VALUE);
            tvThoughtContent.setEllipsize(null);
        } else {
            tvThoughtContent.setMaxLines(THOUGHT_CONTENT_COLLAPSED_MAX_LINES);
            tvThoughtContent.setEllipsize(TextUtils.TruncateAt.END);
        }
        TextView tvCodeArea = helper.getView(R.id.aiw_tvCodeArea);
        tvCodeArea.setTypeface(Typeface.MONOSPACE);
        tvCodeArea.setText(sanitizeWorkbenchText(resolvedCodeBlock));
        tvCodeArea.setMovementMethod(new ScrollingMovementMethod());
        tvCodeArea.setEnabled(!codeAreaLocked);
        tvCodeArea.setOnTouchListener((v, event) -> {
            if (codeAreaLocked) {
                return true;
            }
            int action = event.getAction();
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });
        tvCodeArea.setVisibility(item.codeExpanded ? View.VISIBLE : View.GONE);
        if (item.codeExpanded && !TextUtils.isEmpty(resolvedCodeBlock)) {
            tvCodeArea.post(() -> {
                if (tvCodeArea.getLayout() == null) {
                    return;
                }
                int scrollAmount = tvCodeArea.getLayout().getLineTop(tvCodeArea.getLineCount()) - tvCodeArea.getHeight();
                tvCodeArea.scrollTo(0, Math.max(scrollAmount, 0));
            });
        }
    }

    private void bindThoughtPayload(BaseViewHolder helper, WorkbenchUiItem item, int mask) {
        if ((mask & (WorkbenchStreamPayload.TITLE | WorkbenchStreamPayload.THOUGHT)) != 0) {
            TextView title = helper.getView(R.id.aiw_tvThoughtTitle);
            boolean trivial = isTrivialCompletedThought(item);
            title.setText(trivial
                    ? displayCompletedThoughtTitle(item.title)
                    : item.thoughtContentExpanded ? item.title + " · 收起" : item.title + " · 展开");
            title.setTextColor(Color.parseColor(trivial ? "#7F8EA8" : "#AAB9D4"));
        }
        if ((mask & WorkbenchStreamPayload.THOUGHT) == 0) {
            return;
        }
        boolean trivial = isTrivialCompletedThought(item);
        boolean hasCode = !TextUtils.isEmpty(item.codeBlock);
        boolean hideBody = trivial && (!hasCode || !item.codeExpanded);
        helper.getView(R.id.aiw_cardThoughtBody).setVisibility(hideBody ? View.GONE : View.VISIBLE);
        TextView codeToggle = helper.getView(R.id.aiw_tvThoughtCodeToggle);
        codeToggle.setVisibility(hasCode ? View.VISIBLE : View.GONE);
        codeToggle.setText(item.codeExpanded ? "收起深度思考" : "查看深度思考");
        if (hideBody) {
            return;
        }
        TextView content = helper.getView(R.id.aiw_tvThoughtContent);
        content.setText(sanitizeWorkbenchText(item.content));
        content.setVisibility(trivial ? View.GONE : View.VISIBLE);
        TextView code = helper.getView(R.id.aiw_tvCodeArea);
        code.setText(sanitizeWorkbenchText(item.codeBlock));
        code.setVisibility(item.codeExpanded ? View.VISIBLE : View.GONE);
    }

    private boolean isTrivialCompletedThought(WorkbenchUiItem item) {
        if (item == null) {
            return false;
        }
        String content = sanitizeWorkbenchText(item.content).trim();
        return "本轮思考完成".equals(content);
    }

    private String displayCompletedThoughtTitle(String title) {
        if (TextUtils.isEmpty(title)) {
            return "思考完成";
        }
        String marker = "轮 · ";
        int markerIndex = title.indexOf(marker);
        if (title.startsWith("第 ") && markerIndex > 0) {
            return title.substring(0, markerIndex + 1) + " · 思考完成";
        }
        return "思考完成";
    }

    private void bindReason(BaseViewHolder helper, WorkbenchUiItem item) {
        helper.setText(R.id.aiw_tvSummaryTitle, displaySummaryTitle(item.title));
        bindSummaryContent(helper, item, true);
        applySummaryVisualState(helper, item,
                Color.parseColor("#F0F6FF"),
                Color.parseColor("#D6E2F7"));
        bindWaitingEffect(helper, item);

        TextView tvMeta = helper.getView(R.id.aiw_tvSummaryMeta);
        TextView tvToggle = helper.getView(R.id.aiw_tvSummaryToggle);
        TextView tvDetail = helper.getView(R.id.aiw_tvSummaryDetail);
        AnimatedNumberView counterView = helper.getView(R.id.aiw_anvSummaryCounter);
        helper.setGone(R.id.aiw_llSearchReplaceDiff, false);

        bindReasonCounter(tvMeta, counterView, item);

        String safeDetailContent = sanitizeWorkbenchText(item.detailContent);
        boolean hasDetail = item.detailExpandable && !TextUtils.isEmpty(safeDetailContent);
        tvToggle.setVisibility(hasDetail ? View.VISIBLE : View.GONE);
        tvToggle.setText(item.detailExpanded ? "收起详情" : "查看详情");

        tvDetail.setTypeface(Typeface.MONOSPACE);
        tvDetail.setMovementMethod(new ScrollingMovementMethod());
        tvDetail.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });
        tvDetail.setText(safeDetailContent);
        tvDetail.setVisibility(hasDetail && item.detailExpanded ? View.VISIBLE : View.GONE);
    }

    private void bindReasonPayload(BaseViewHolder helper, WorkbenchUiItem item, int mask) {
        if ((mask & WorkbenchStreamPayload.TITLE) != 0) {
            helper.setText(R.id.aiw_tvSummaryTitle, displaySummaryTitle(item.title));
        }
        if ((mask & WorkbenchStreamPayload.CONTENT) != 0) {
            bindSummaryContent(helper, item, true);
        }
        if ((mask & WorkbenchStreamPayload.COUNTER) != 0) {
            bindReasonCounter(
                    helper.getView(R.id.aiw_tvSummaryMeta),
                    helper.getView(R.id.aiw_anvSummaryCounter),
                    item);
        }
        if ((mask & WorkbenchStreamPayload.AURA) != 0) {
            bindWaitingEffect(helper, item);
        }
    }

    private void bindReasonCounter(TextView label, AnimatedNumberView counter, WorkbenchUiItem item) {
        if (item.showProgressCounter) {
            label.setVisibility(View.VISIBLE);
            label.setText(TextUtils.isEmpty(item.progressCounterLabel) ? "已写入" : item.progressCounterLabel);
            counter.setVisibility(View.VISIBLE);
            counter.setNumber(item.progressCounterValue);
            return;
        }
        label.setVisibility(View.GONE);
        counter.cancelAnimations();
        counter.setNumber(0L, false);
        counter.setVisibility(View.GONE);
    }

    private void bindEditNotice(BaseViewHolder helper, WorkbenchUiItem item) {
        helper.setText(R.id.aiw_tvSummaryTitle, displaySummaryTitle(item.title));
        bindSummaryContent(helper, item, true);
        applySummaryVisualState(helper, item,
                Color.parseColor("#86EFAC"),
                Color.parseColor("#DCFCE7"));
        helper.setGone(R.id.aiw_tvSummaryMeta, false);
        helper.setGone(R.id.aiw_anvSummaryCounter, false);
        helper.setGone(R.id.aiw_tvSummaryToggle, false);
        helper.setGone(R.id.aiw_tvSummaryDetail, false);
        clearWaitingEffect(helper);
        bindSearchReplaceDiff(helper, item);
        bindSummaryAction(helper, item);
    }

    private void bindSummaryContent(BaseViewHolder helper, WorkbenchUiItem item, boolean allowCollapse) {
        TextView contentView = helper.getView(R.id.aiw_tvSummaryContent);
        TextView toggleView = helper.getView(R.id.aiw_tvSummaryContentToggle);
        String sourceContent =
                item.contentExpanded
                        || !WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA.equals(item.waitingEffect)
                        ? item.resolvedContent()
                        : item.content;
        String safeContent = sanitizeWorkbenchText(sourceContent);
        String displayContent = streamingCollapsedPreview(item, safeContent);
        contentView.setText(displayContent);

        boolean expandable = allowCollapse && shouldCollapseSummaryContent(safeContent);
        toggleView.setVisibility(expandable ? View.VISIBLE : View.GONE);
        if (expandable) {
            contentView.setMaxLines(item.contentExpanded ? Integer.MAX_VALUE : SUMMARY_CONTENT_COLLAPSED_MAX_LINES);
            contentView.setEllipsize(item.contentExpanded ? null : TextUtils.TruncateAt.END);
            toggleView.setText(item.contentExpanded ? "收起" : "展开");
            toggleView.setOnClickListener(v -> {
                item.contentExpanded = !item.contentExpanded;
                int position = helper.getLayoutPosition();
                if (position >= 0) {
                    notifyItemChanged(position);
                }
                notifyPresentationStateChanged(item);
            });
        } else {
            contentView.setMaxLines(Integer.MAX_VALUE);
            contentView.setEllipsize(null);
            toggleView.setOnClickListener(null);
        }
    }

    private String streamingCollapsedPreview(WorkbenchUiItem item, String safeContent) {
        if (item == null
                || item.contentExpanded
                || !item.showProgressCounter
                || !WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA.equals(item.waitingEffect)
                || safeContent.length() <= STREAMING_COLLAPSED_PREVIEW_MAX_CHARS) {
            return safeContent;
        }
        return safeContent.substring(0, STREAMING_COLLAPSED_PREVIEW_MAX_CHARS) + "…";
    }

    private boolean shouldCollapseSummaryContent(String content) {
        if (TextUtils.isEmpty(content)) {
            return false;
        }
        if (content.length() > SUMMARY_CONTENT_COLLAPSED_MIN_CHARS) {
            return true;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
                if (lines > SUMMARY_CONTENT_COLLAPSED_MAX_LINES) {
                    return true;
                }
            }
        }
        return false;
    }

    private String displaySummaryTitle(String title) {
        if (TextUtils.isEmpty(title)) {
            return title;
        }
        String marker = "轮 · ";
        int markerIndex = title.indexOf(marker);
        if (!title.startsWith("第 ") || markerIndex < 0) {
            return title;
        }
        String stage = title.substring(markerIndex + marker.length()).trim();
        if ("工具结果".equals(stage)
                || "已拦截风险操作".equals(stage)
                || "需要换一种方式继续".equals(stage)
                || "任务中断".equals(stage)
                || "终态审核".equals(stage)
                || "终态审核通过".equals(stage)
                || "终态审核未通过".equals(stage)
                || "终态审核无效".equals(stage)) {
            return stage;
        }
        return title;
    }

    private void bindSearchReplaceDiff(BaseViewHolder helper, WorkbenchUiItem item) {
        LinearLayout diffContainer = helper.getView(R.id.aiw_llSearchReplaceDiff);
        LinearLayout linesContainer = helper.getView(R.id.aiw_llSearchReplaceDiffLines);
        linesContainer.removeAllViews();
        boolean visible = item.diffVisible && !TextUtils.isEmpty(item.diffText);
        diffContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            return;
        }
        helper.setText(R.id.aiw_tvSearchReplaceDiffTitle, TextUtils.isEmpty(item.diffTitle) ? "代码变更" : item.diffTitle);
        helper.setText(R.id.aiw_tvSearchReplaceDiffMeta, item.diffMeta);
        TextView diffToggleView = helper.getView(R.id.aiw_tvSearchReplaceDiffToggle);
        Context context = linesContainer.getContext();
        String[] lines = item.diffText.split("\\n", -1);
        int visibleLineCount = 0;
        int nonEmptyLineCount = 0;
        for (String line : lines) {
            if (TextUtils.isEmpty(line)) {
                continue;
            }
            nonEmptyLineCount++;
        }
        boolean expandable = nonEmptyLineCount > DIFF_COLLAPSED_MAX_LINES;
        int maxLines = item.diffExpanded ? Integer.MAX_VALUE : DIFF_COLLAPSED_MAX_LINES;
        for (String line : lines) {
            if (TextUtils.isEmpty(line)) {
                continue;
            }
            if (visibleLineCount >= maxLines) {
                continue;
            }
            visibleLineCount++;
            TextView lineView = new TextView(context);
            lineView.setText(line);
            lineView.setTypeface(Typeface.MONOSPACE);
            lineView.setTextSize(12);
            lineView.setSingleLine(false);
            lineView.setPadding(dp(mContext, 8), dp(mContext, 2), dp(mContext, 8), dp(mContext, 2));
            char marker = line.charAt(0);
            if (marker == '+') {
                lineView.setTextColor(Color.parseColor("#86EFAC"));
                lineView.setBackgroundColor(Color.parseColor("#10281B"));
            } else if (marker == '-') {
                lineView.setTextColor(Color.parseColor("#FCA5A5"));
                lineView.setBackgroundColor(Color.parseColor("#2B171B"));
            } else {
                lineView.setTextColor(Color.parseColor("#CBD5E1"));
                lineView.setBackgroundColor(Color.parseColor("#111827"));
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lineView.setLayoutParams(params);
            linesContainer.addView(lineView);
        }
        diffToggleView.setVisibility(expandable ? View.VISIBLE : View.GONE);
        if (expandable) {
            int hiddenCount = Math.max(0, nonEmptyLineCount - DIFF_COLLAPSED_MAX_LINES);
            diffToggleView.setText(item.diffExpanded ? "收起变更" : "展开变更（还有 " + hiddenCount + " 行）");
            diffToggleView.setOnClickListener(v -> {
                item.diffExpanded = !item.diffExpanded;
                int position = helper.getLayoutPosition();
                if (position >= 0) {
                    notifyItemChanged(position);
                }
                notifyPresentationStateChanged(item);
            });
        } else {
            diffToggleView.setOnClickListener(null);
        }
    }

    private void bindBrowserTest(BaseViewHolder helper, WorkbenchUiItem item) {
        helper.setText(R.id.aiw_tvBrowserTestTitle, TextUtils.isEmpty(item.title) ? "浏览器测试" : item.title);
        helper.setText(R.id.aiw_tvBrowserTestStatus, browserStatusText(item.browserTestStatus));
        helper.setText(R.id.aiw_tvBrowserTestContent, sanitizeWorkbenchText(item.content));
        helper.setText(R.id.aiw_tvBrowserTestMeta, sanitizeWorkbenchText(item.browserTestMeta));
        TextView statusView = helper.getView(R.id.aiw_tvBrowserTestStatus);
        statusView.setTextColor(browserStatusTextColor(item.browserTestStatus));
        statusView.setBackground(makeRoundDrawable(browserStatusBgColor(item.browserTestStatus), 999));

        LinearLayout stepsContainer = helper.getView(R.id.aiw_llBrowserTestSteps);
        stepsContainer.removeAllViews();
        if (item.steps == null || item.steps.isEmpty()) {
            return;
        }
        Context context = stepsContainer.getContext();
        int count = Math.min(item.steps.size(), 6);
        for (int i = 0; i < count; i++) {
            BrowserStep step = parseBrowserStep(item.steps.get(i));
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(mContext, i == 0 ? 2 : 7), 0, 0);

            TextView dot = new TextView(context);
            dot.setText(browserStepIcon(step.status));
            dot.setTextColor(browserStepColor(step.status));
            dot.setTextSize(13);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(mContext, 24), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(dot, dotParams);

            TextView label = new TextView(context);
            label.setText(step.label);
            label.setTextSize(13);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setTextColor(browserStepTextColor(step.status));
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            stepsContainer.addView(row);
        }
    }

    private BrowserStep parseBrowserStep(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return new BrowserStep("pending", "等待执行");
        }
        int split = raw.indexOf("::");
        if (split <= 0) {
            return new BrowserStep("pending", raw);
        }
        return new BrowserStep(raw.substring(0, split), raw.substring(split + 2));
    }

    private String browserStatusText(String status) {
        if ("success".equals(status)) return "通过";
        if ("error".equals(status)) return "失败";
        if ("blocked".equals(status)) return "阻塞";
        return "运行中";
    }

    private int browserStatusTextColor(String status) {
        if ("success".equals(status)) return Color.parseColor("#BBF7D0");
        if ("error".equals(status) || "blocked".equals(status)) return Color.parseColor("#FECACA");
        return Color.parseColor("#BFDBFE");
    }

    private int browserStatusBgColor(String status) {
        if ("success".equals(status)) return Color.parseColor("#17442A");
        if ("error".equals(status) || "blocked".equals(status)) return Color.parseColor("#4A1F25");
        return Color.parseColor("#1E3A5F");
    }

    private String browserStepIcon(String status) {
        if ("success".equals(status)) return "✓";
        if ("error".equals(status)) return "!";
        if ("running".equals(status)) return "●";
        return "○";
    }

    private int browserStepColor(String status) {
        if ("success".equals(status)) return Color.parseColor("#86EFAC");
        if ("error".equals(status)) return Color.parseColor("#FCA5A5");
        if ("running".equals(status)) return Color.parseColor("#60A5FA");
        return Color.parseColor("#64748B");
    }

    private int browserStepTextColor(String status) {
        if ("error".equals(status)) return Color.parseColor("#FECACA");
        if ("running".equals(status)) return Color.parseColor("#DBEAFE");
        if ("success".equals(status)) return Color.parseColor("#DCFCE7");
        return Color.parseColor("#94A3B8");
    }

    private GradientDrawable makeRoundDrawable(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(mContext, radiusDp));
        return drawable;
    }

    private static final class BrowserStep {
        final String status;
        final String label;

        BrowserStep(String status, String label) {
            this.status = TextUtils.isEmpty(status) ? "pending" : status;
            this.label = TextUtils.isEmpty(label) ? "执行操作" : label;
        }
    }

    private void hideSummaryExtras(BaseViewHolder helper) {
        helper.setGone(R.id.aiw_tvSummaryMeta, false);
        helper.setGone(R.id.aiw_anvSummaryCounter, false);
        helper.setGone(R.id.aiw_tvSummaryToggle, false);
        helper.setGone(R.id.aiw_tvSummaryDetail, false);
        helper.setGone(R.id.aiw_tvSummaryAction, false);
        helper.setGone(R.id.aiw_llSearchReplaceDiff, false);
        clearWaitingEffect(helper);
    }

    private void bindWaitingEffect(BaseViewHolder helper, WorkbenchUiItem item) {
        WorkbenchWaitingAuraView auraView = helper.getView(R.id.aiw_vSummaryWaitingAura);
        boolean active = item != null
                && WorkbenchUiItem.WAITING_EFFECT_TOOL_AURA.equals(item.waitingEffect);
        auraView.setVisibility(active ? View.VISIBLE : View.GONE);
        auraView.setActive(active, item == null ? 0L : item.waitingEffectStartedAtMs);
    }

    private void clearWaitingEffect(BaseViewHolder helper) {
        WorkbenchWaitingAuraView auraView = helper.getView(R.id.aiw_vSummaryWaitingAura);
        auraView.setActive(false);
        auraView.resetAnimationPhase();
        auraView.setVisibility(View.GONE);
    }

    private void applySummaryVisualState(BaseViewHolder helper, WorkbenchUiItem item, int normalTitleColor, int normalContentColor) {
        ImageView statusIconView = helper.getView(R.id.aiw_ivSummaryStatusIcon);
        int resolvedIcon = resolveStatusIcon(item);
        if (resolvedIcon != 0) {
            statusIconView.setVisibility(View.VISIBLE);
            statusIconView.setImageResource(resolvedIcon);
        } else {
            statusIconView.setVisibility(View.GONE);
            statusIconView.setImageDrawable(null);
        }
        String statusLevel = normalizeStatusLevel(item);
        if (WorkbenchUiItem.STATUS_ERROR.equals(statusLevel)) {
            helper.setTextColor(R.id.aiw_tvSummaryTitle, Color.parseColor("#FCA5A5"));
            helper.setTextColor(R.id.aiw_tvSummaryContent, Color.parseColor("#FECACA"));
            return;
        }
        if (WorkbenchUiItem.STATUS_WARNING.equals(statusLevel)) {
            helper.setTextColor(R.id.aiw_tvSummaryTitle, Color.parseColor("#FDE68A"));
            helper.setTextColor(R.id.aiw_tvSummaryContent, Color.parseColor("#FEF3C7"));
            return;
        }
        if (WorkbenchUiItem.STATUS_SUCCESS.equals(statusLevel)) {
            helper.setTextColor(R.id.aiw_tvSummaryTitle, Color.parseColor("#86EFAC"));
            helper.setTextColor(R.id.aiw_tvSummaryContent, Color.parseColor("#DCFCE7"));
            return;
        }
        helper.setTextColor(R.id.aiw_tvSummaryTitle, normalTitleColor);
        helper.setTextColor(R.id.aiw_tvSummaryContent, normalContentColor);
    }

    private int resolveStatusIcon(WorkbenchUiItem item) {
        if (item == null) {
            return 0;
        }
        if (item.summaryIconResId != 0) {
            return item.summaryIconResId;
        }
        String statusLevel = normalizeStatusLevel(item);
        if (WorkbenchUiItem.STATUS_SUCCESS.equals(statusLevel)) {
            return R.drawable.aiw_ic_status_success;
        }
        if (WorkbenchUiItem.STATUS_WARNING.equals(statusLevel)) {
            return R.drawable.aiw_ic_status_warning;
        }
        if (WorkbenchUiItem.STATUS_ERROR.equals(statusLevel)) {
            return R.drawable.aiw_ic_status_error;
        }
        return 0;
    }

    private String normalizeStatusLevel(WorkbenchUiItem item) {
        if (item == null) {
            return WorkbenchUiItem.STATUS_NORMAL;
        }
        if (WorkbenchUiItem.STATUS_SUCCESS.equals(item.statusLevel)
                || WorkbenchUiItem.STATUS_WARNING.equals(item.statusLevel)
                || WorkbenchUiItem.STATUS_ERROR.equals(item.statusLevel)) {
            return item.statusLevel;
        }
        return item.errorState ? WorkbenchUiItem.STATUS_ERROR : WorkbenchUiItem.STATUS_NORMAL;
    }

    private void bindSummaryAction(BaseViewHolder helper, WorkbenchUiItem item) {
        TextView actionView = helper.getView(R.id.aiw_tvSummaryAction);
        boolean visible = item.actionVisible && !TextUtils.isEmpty(item.actionText);
        actionView.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            actionView.setOnClickListener(null);
            return;
        }
        actionView.setText(item.actionText);
        actionView.setEnabled(item.actionEnabled);
        actionView.setAlpha(item.actionEnabled ? 1f : 0.55f);
        actionView.setOnClickListener(v -> {
            if (item.actionEnabled && onSummaryActionClickListener != null) {
                onSummaryActionClickListener.onSummaryActionClick(item, helper.getLayoutPosition());
            }
        });
    }

    private void bindItemLongClick(BaseViewHolder helper, WorkbenchUiItem item) {
        helper.itemView.setOnLongClickListener(v -> {
            if (onItemLongClickListener == null) {
                return false;
            }
            onItemLongClickListener.onItemLongClick(item, helper.getLayoutPosition());
            return true;
        });
    }

    private String sanitizeWorkbenchText(String text) {
        return sanitizeDisplayText(text);
    }

    public void setOnThoughtItemClickListener(OnThoughtItemClickListener listener) {
        this.onThoughtItemClickListener = listener;
    }

    public void setOnReasonItemClickListener(OnReasonItemClickListener listener) {
        this.onReasonItemClickListener = listener;
    }

    public void setOnSummaryActionClickListener(OnSummaryActionClickListener listener) {
        this.onSummaryActionClickListener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.onItemLongClickListener = listener;
    }

    public void setOnPresentationStateChangedListener(OnPresentationStateChangedListener listener) {
        this.onPresentationStateChangedListener = listener;
    }

    private void notifyPresentationStateChanged(WorkbenchUiItem item) {
        if (onPresentationStateChangedListener != null) {
            onPresentationStateChangedListener.onPresentationStateChanged(item);
        }
    }

    public interface OnThoughtItemClickListener {
        void onThoughtItemClick(WorkbenchUiItem item, int position);
    }

    public interface OnReasonItemClickListener {
        void onReasonItemClick(WorkbenchUiItem item, int position);
    }

    public interface OnSummaryActionClickListener {
        void onSummaryActionClick(WorkbenchUiItem item, int position);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(WorkbenchUiItem item, int position);
    }

    public interface OnPresentationStateChangedListener {
        void onPresentationStateChanged(WorkbenchUiItem item);
    }

    public void setCodeAreaLocked(boolean locked) {
        if (codeAreaLocked == locked) {
            return;
        }
        codeAreaLocked = locked;
        notifyDataSetChanged();
    }

    void setUserAvatarUrl(String value) {
        String next = value == null ? "" : value;
        if (next.equals(userAvatarUrl)) return;
        userAvatarUrl = next;
        notifyDataSetChanged();
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String sanitizeDisplayText(String text) {
        if (TextUtils.isEmpty(text)) return text;
        int marker = text.indexOf("[native_tool_calls]");
        return marker < 0 ? text : text.substring(0, marker).trim();
    }

}
