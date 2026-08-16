package com.application.membershipmodule.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;

import com.application.membershipmodule.benefit.service.BenefitPolicy;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * docs/lld/03-benefit-policy-engine.md §5 Test 2 — mirrors
 * {@code TierEvaluationArchitectureTest}. {@code BenefitResolutionService} must not depend on any
 * concrete {@code BenefitPolicy} implementation class (e.g. {@code FreeDeliveryPolicy}), only the
 * interface and the registry.
 */
class BenefitResolutionArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.application.membershipmodule.benefit");

    @Test
    void benefitResolutionServiceMustNotDependOnConcretePolicies() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("BenefitResolutionService")
                .should().dependOnClassesThat().implement(BenefitPolicy.class)
                .because("BenefitResolutionService must depend only on the BenefitPolicy interface "
                        + "and the registry, never on a concrete policy class");

        rule.check(classes);
    }
}
