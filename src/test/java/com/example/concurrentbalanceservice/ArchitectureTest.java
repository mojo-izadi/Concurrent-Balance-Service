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
	static final ArchRule servicesAvoidWebAndPersistenceDetails = noClasses().that().resideInAPackage("..service..")
			.should().dependOnClassesThat().resideInAnyPackage("..controller..", "..dto..", "..config..",
					"..repository.entity..", "..repository.mapper..");

	@ArchTest
	static final ArchRule repositoriesAvoidWebAndServiceImplementations = noClasses().that().resideInAPackage("..repository..")
			.should().dependOnClassesThat().resideInAnyPackage("..controller..", "..dto..", "..config..", "..service");

	@ArchTest
	static final ArchRule controllersUseServices = noClasses().that().resideInAPackage("..controller..")
			.should().dependOnClassesThat().resideInAnyPackage("..repository..", "..execution..", "..config..", "..domain..");

	@ArchTest
	static final ArchRule persistenceDetailsStayInsideRepositories = noClasses().that()
			.resideOutsideOfPackage("..repository..")
			.should().dependOnClassesThat().resideInAnyPackage("..repository.entity..", "..repository.mapper..");

	@ArchTest
	static final ArchRule repositoriesDoNotUseJdbc = noClasses().that().resideInAPackage("..repository..")
			.should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..");
}
