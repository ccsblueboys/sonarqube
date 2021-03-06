/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.computation.task.projectanalysis.formula;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.server.computation.task.projectanalysis.component.CrawlerDepthLimit;
import org.sonar.server.computation.task.projectanalysis.formula.counter.DoubleVariationValue;
import org.sonar.server.computation.task.projectanalysis.measure.Measure;
import org.sonar.server.computation.task.projectanalysis.measure.MeasureVariations;
import org.sonar.server.computation.task.projectanalysis.period.Period;

import static com.google.common.collect.FluentIterable.from;
import static java.util.Objects.requireNonNull;
import static org.sonar.server.computation.task.projectanalysis.measure.Measure.newMeasureBuilder;

/**
 * A Formula which aggregates variations of a specific metric by simply making the sums of its variations. It supports
 * make the sum of only specific periods.
 */
public class VariationSumFormula implements Formula<VariationSumFormula.VariationSumCounter> {
  private final String metricKey;
  private final Predicate<Period> supportedPeriods;
  @CheckForNull
  private final Double defaultInputValue;

  public VariationSumFormula(String metricKey, Predicate<Period> supportedPeriods) {
    this(metricKey, supportedPeriods, null);
  }

  public VariationSumFormula(String metricKey, Predicate<Period> supportedPeriods, @Nullable Double defaultInputValue) {
    this.metricKey = requireNonNull(metricKey, "Metric key cannot be null");
    this.supportedPeriods = requireNonNull(supportedPeriods, "Period predicate can not be null");
    this.defaultInputValue = defaultInputValue;
  }

  @Override
  public VariationSumCounter createNewCounter() {
    return new VariationSumCounter(metricKey, supportedPeriods, defaultInputValue);
  }

  @Override
  public Optional<Measure> createMeasure(VariationSumCounter counter, CreateMeasureContext context) {
    if (!CrawlerDepthLimit.LEAVES.isDeeperThan(context.getComponent().getType())) {
      return Optional.absent();
    }
    MeasureVariations.Builder variations = createAndPopulateBuilder(counter.array, context);
    if (variations.isEmpty()) {
      return Optional.absent();
    }
    return Optional.of(newMeasureBuilder().setVariations(variations.build()).createNoValue());
  }

  private MeasureVariations.Builder createAndPopulateBuilder(DoubleVariationValue.Array array, CreateMeasureContext context) {
    MeasureVariations.Builder builder = MeasureVariations.newMeasureVariationsBuilder();
    for (Period period : from(context.getPeriods()).filter(supportedPeriods)) {
      DoubleVariationValue elements = array.get(period);
      if (elements.isSet()) {
        builder.setVariation(period, elements.getValue());
      }
    }
    return builder;
  }

  @Override
  public String[] getOutputMetricKeys() {
    return new String[] {metricKey};
  }

  public static final class VariationSumCounter implements Counter<VariationSumCounter> {
    @CheckForNull
    private final Double defaultInputValue;
    private final DoubleVariationValue.Array array = DoubleVariationValue.newArray();
    private final String metricKey;
    private final Predicate<Period> supportedPeriods;

    private VariationSumCounter(String metricKey, Predicate<Period> supportedPeriods, @Nullable Double defaultInputValue) {
      this.metricKey = metricKey;
      this.supportedPeriods = supportedPeriods;
      this.defaultInputValue = defaultInputValue;
    }

    @Override
    public void aggregate(VariationSumCounter counter) {
      array.incrementAll(counter.array);
    }

    @Override
    public void initialize(CounterInitializationContext context) {
      Optional<Measure> measure = context.getMeasure(metricKey);
      if (!measure.isPresent() || !measure.get().hasVariations()) {
        initializeWithDefaultInputValue(context);
        return;
      }
      MeasureVariations variations = measure.get().getVariations();
      for (Period period : from(context.getPeriods()).filter(supportedPeriods)) {
        if (variations.hasVariation(period.getIndex())) {
          double variation = variations.getVariation(period.getIndex());
          if (variation > 0) {
            array.increment(period, variation);
          } else if (defaultInputValue != null) {
            array.increment(period, defaultInputValue);
          }
        }
      }
    }

    private void initializeWithDefaultInputValue(CounterInitializationContext context) {
      if (defaultInputValue == null) {
        return;
      }
      for (Period period : from(context.getPeriods()).filter(supportedPeriods)) {
        array.increment(period, defaultInputValue);
      }
    }
  }
}
