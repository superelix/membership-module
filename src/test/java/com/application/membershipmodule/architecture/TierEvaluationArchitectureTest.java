package com.application.membershipmodule.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;

import com.application.membershipmodule.tier.service.TierCriterionEvaluator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * docs/lld/02-tier-evaluation-engine.md §6 Test 2 (structural proof). Catches the regression the
 * behavioral extensibility test cannot: someone reintroducing a hardcoded reference to a concrete
 * {@code TierCriterionEvaluator} implementation (e.g. {@code OrderCountMinEvaluator}) inside the
 * orchestration classes, bypassing the registry — even if it still happens to pass every
 * behavioral MP-AC-* scenario because the switch also happens to cover the real types.
 */
class TierEvaluationArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.application.membershipmodule.tier");

    @Test
    void tierEvaluationOrchestrationMustNotDependOnConcreteCriterionEvaluators() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("TierEvaluationService")
                .or().haveSimpleName("TierEvaluationTransactionalOps")
                .should().dependOnClassesThat().implement(TierCriterionEvaluator.class)
                .because("TierEvaluationService/TierEvaluationTransactionalOps must depend only on the "
                        + "TierCriterionEvaluator interface and the registry, never on a concrete evaluator class");

        rule.check(classes);
    }
}
