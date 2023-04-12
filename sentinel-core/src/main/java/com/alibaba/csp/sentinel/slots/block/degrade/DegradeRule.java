/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.degrade;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;

/**
 * <p>
 * Degrade is used when the resources are in an unstable state, these resources
 * will be degraded within the next defined time window. There are two ways to
 * measure whether a resource is stable or not:
 * 当资源处于不稳定状态时使用降级，这些资源将在下一个定义的时间范围内降级。有两种方法可以衡量资源是否稳定：
 * </p>
 * <ul>
 * <li>
 * Average response time ({@code DEGRADE_GRADE_RT}): When
 * the average RT exceeds the threshold ('count' in 'DegradeRule', in milliseconds), the
 * resource enters a quasi-degraded state. If the RT of next coming 5
 * requests still exceed this threshold, this resource will be downgraded, which
 * means that in the next time window (defined in 'timeWindow', in seconds) all the
 * access to this resource will be blocked.
 * 平均响应时间 （DEGRADE_GRADE_RT）：当平均 RT 超过阈值（“降级规则”中的“计数”，以毫秒为单位）时，资源将进入准降级状态。如果接下来的 5 个请求的 RT 仍然超过此阈值，则该资源将被降级，这意味着在下一个时间窗口（在“timeWindow”中定义，以秒为单位）中，将阻止对该资源的所有访问。
 * </li>
 * <li>
 * Exception ratio: When the ratio of exception count per second and the
 * success qps exceeds the threshold, access to the resource will be blocked in
 * the coming window.
 * 异常率：当每秒异常计数与成功qps的比率超过阈值时，在下一个窗口中将阻止对资源的访问。
 * </li>
 * </ul>
 *
 * @author jialiang.linjl
 */
public class DegradeRule extends AbstractRule {

    private static final int RT_MAX_EXCEED_N = 5;

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("sentinel-degrade-reset-task", true));

    public DegradeRule() {}

    public DegradeRule(String resourceName) {
        setResource(resourceName);
    }

    /**
     * RT threshold or exception ratio threshold count.
     * RT 阈值或异常比率阈值计数。
     */
    private double count;

    /**
     * Degrade recover timeout (in seconds) when degradation occurs.
     * 降级时恢复超时（以秒为单位）。
     */
    private int timeWindow;

    /**
     * Degrade strategy (0: average RT, 1: exception ratio).
     * 降级策略（0：平均RT，1：异常比率）。
     */
    private int grade = RuleConstant.DEGRADE_GRADE_RT;

    private final AtomicBoolean cut = new AtomicBoolean(false);

    public int getGrade() {
        return grade;
    }

    public DegradeRule setGrade(int grade) {
        this.grade = grade;
        return this;
    }

    private AtomicLong passCount = new AtomicLong(0);

    public double getCount() {
        return count;
    }

    public DegradeRule setCount(double count) {
        this.count = count;
        return this;
    }

    private boolean isCut() {
        return cut.get();
    }

    private void setCut(boolean cut) {
        this.cut.set(cut);
    }

    public AtomicLong getPassCount() {
        return passCount;
    }

    public int getTimeWindow() {
        return timeWindow;
    }

    public DegradeRule setTimeWindow(int timeWindow) {
        this.timeWindow = timeWindow;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DegradeRule)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        DegradeRule that = (DegradeRule)o;

        if (count != that.count) {
            return false;
        }
        if (timeWindow != that.timeWindow) {
            return false;
        }
        if (grade != that.grade) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + new Double(count).hashCode();
        result = 31 * result + timeWindow;
        result = 31 * result + grade;
        return result;
    }

    @Override
    public boolean passCheck(Context context, DefaultNode node, int acquireCount, Object... args) {
        if (cut.get()) {
            return false;
        }

        ClusterNode clusterNode = ClusterBuilderSlot.getClusterNode(this.getResource());
        if (clusterNode == null) {
            return true;
        }

        if (grade == RuleConstant.DEGRADE_GRADE_RT) {
            double rt = clusterNode.avgRt();
            // 平均响应时间 < 阈值，不会触发降级
            if (rt < this.count) {
                passCount.set(0);
                return true;
            }

            // Sentinel will degrade the service only if count exceeds.
            // 当平均响应时间大于阈值，但通过数小于5，不会触发降级。 只有平均响应时间大于阈值的通过数量>5才会触发降级
            if (passCount.incrementAndGet() < RT_MAX_EXCEED_N) {
                return true;
            }

            // RT规则熔断触发时机: 平均响应时间>阈值，窗口时间内通过数>5次

        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO) {
            double exception = clusterNode.exceptionQps();
            double success = clusterNode.successQps();
            double total = clusterNode.totalQps();
            // if total qps less than RT_MAX_EXCEED_N, pass.
            // 总的qps<5，不会触发降级
            if (total < RT_MAX_EXCEED_N) {
                return true;
            }

            double realSuccess = success - exception;
            // 异常qps<5，不会触发降级
            if (realSuccess <= 0 && exception < RT_MAX_EXCEED_N) {
                return true;
            }
            // 异常比例 < 阈值，不会触发降级
            if (exception / success < count) {
                return true;
            }
            // 异常比例规则熔断触发时机: 总的qps>=5,异常qps>=5，异常比例>阈值

        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT) {
            double exception = clusterNode.totalException();
            // 异常数 < 阈值不会触发降级
            if (exception < count) {
                return true;
            }

            // 异常数规则熔断触发时间: 1分钟内，异常数>阈值
        }

        // 触发降级时间窗口
        if (cut.compareAndSet(false, true)) {
            ResetTask resetTask = new ResetTask(this);
            pool.schedule(resetTask, timeWindow, TimeUnit.SECONDS);
        }
        // 降级
        return false;
    }

    @Override
    public String toString() {
        return "DegradeRule{" +
            "resource=" + getResource() +
            ", grade=" + grade +
            ", count=" + count +
            ", limitApp=" + getLimitApp() +
            ", timeWindow=" + timeWindow +
            "}";
    }

    private static final class ResetTask implements Runnable {

        private DegradeRule rule;

        ResetTask(DegradeRule rule) {
            this.rule = rule;
        }

        @Override
        public void run() {
            rule.getPassCount().set(0);
            rule.cut.set(false);
        }
    }
}

