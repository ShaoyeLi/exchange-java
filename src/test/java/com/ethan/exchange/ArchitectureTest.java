package com.ethan.exchange;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    @Test
    void matchingDomainDoesNotDependOnSpringOrInfrastructure() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ethan.exchange");

        noClasses()
                .that().resideInAPackage("..matching..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.apache.kafka..",
                        "org.web3j..")
                .check(classes);

        noClasses()
                .that().resideInAPackage("..matching.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ethan.exchange.matching.engine..",
                        "com.ethan.exchange.api..",
                        "com.ethan.exchange.order..",
                        "com.ethan.exchange.ledger..",
                        "com.ethan.exchange.settlement..",
                        "com.ethan.exchange.messaging..",
                        "com.ethan.exchange.chain..")
                .check(classes);
    }
}
