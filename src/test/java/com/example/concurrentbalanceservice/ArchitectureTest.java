package com.example.concurrentbalanceservice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.example.concurrentbalanceservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
	@ArchTest
	static final ArchRule domainIsIndependent = classes().that().resideInAPackage("..domain..")
			.should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..domain..");

	@ArchTest
	static final ArchRule applicationDependsOnlyOnCoreAndJava = classes().that().resideInAPackage("..application..")
			.should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..application..", "..domain..");

	@ArchTest
	static final ArchRule persistenceDoesNotDependOnInboundAdapters = noClasses().that().resideInAPackage("..adapters.persistence..")
			.should().dependOnClassesThat().resideInAnyPackage("..adapters.controller..", "..adapters.http..", "..infrastructure..", "..application.service..");

	@ArchTest
	static final ArchRule controllersUseApplicationServices = noClasses().that().resideInAPackage("..adapters.controller..")
			.should().dependOnClassesThat().resideInAnyPackage("..application.port..", "..adapters.persistence..", "..infrastructure..", "..domain..");

	@ArchTest
	static final ArchRule persistenceTypesStayInsidePersistence = noClasses().that()
			.resideOutsideOfPackage("..adapters.persistence..")
			.should().dependOnClassesThat().resideInAPackage("..adapters.persistence..");

	@ArchTest
	static final ArchRule adaptersDoNotUseJdbc = noClasses().that().resideInAPackage("..adapters..")
			.should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..");
}
