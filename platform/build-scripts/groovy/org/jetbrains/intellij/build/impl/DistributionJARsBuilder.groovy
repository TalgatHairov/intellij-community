// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.intellij.build.impl

import com.intellij.openapi.util.MultiValuesMap
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import groovy.io.FileType
import org.apache.tools.ant.types.FileSet
import org.apache.tools.ant.types.resources.FileProvider
import org.jetbrains.annotations.Nullable
import org.jetbrains.intellij.build.*
import org.jetbrains.intellij.build.fus.StatisticsRecorderBundledWhiteListProvider
import org.jetbrains.jps.model.java.JpsJavaClasspathKind
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.library.JpsLibrary
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.model.module.JpsLibraryDependency
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.jps.model.module.JpsModuleReference
import org.jetbrains.jps.util.JpsPathUtil

import java.util.stream.Collectors
/**
 * Assembles output of modules to platform JARs (in {@link org.jetbrains.intellij.build.BuildPaths#distAll distAll}/lib directory),
 * bundled plugins' JARs (in {@link org.jetbrains.intellij.build.BuildPaths#distAll distAll}/plugins directory) and zip archives with
 * non-bundled plugins (in {@link org.jetbrains.intellij.build.BuildPaths#artifacts artifacts}/plugins directory).
 *
 * @author nik
 */
class DistributionJARsBuilder {
  private static final boolean COMPRESS_JARS = false
  private static final String RESOURCES_INCLUDED = "resources.included"
  private static final String RESOURCES_EXCLUDED = "resources.excluded"
  /**
   * Path to file with third party libraries HTML content,
   * see the same constant at com.intellij.ide.actions.AboutPopup#THIRD_PARTY_LIBRARIES_FILE_PATH
   */
  private static final String THIRD_PARTY_LIBRARIES_FILE_PATH = "license/third-party-libraries.html"
  private static final String PLUGINS_DIRECTORY = "/plugins"

  private final BuildContext buildContext
  private final Set<String> usedModules = new LinkedHashSet<>()
  private final PlatformLayout platform
  private final File patchedApplicationInfo
  private final LinkedHashMap<PluginLayout, PluginPublishingSpec> pluginsToPublish

  DistributionJARsBuilder(BuildContext buildContext, File patchedApplicationInfo,
                          LinkedHashMap<PluginLayout, PluginPublishingSpec> pluginsToPublish = [:]) {
    this.patchedApplicationInfo = patchedApplicationInfo
    this.buildContext = buildContext
    this.pluginsToPublish = pluginsToPublish
    buildContext.ant.patternset(id: RESOURCES_INCLUDED) {
      include(name: "**/*Bundle*.properties")
      include(name: "**/*Messages.properties")
      include(name: "messages/**/*.properties")
      include(name: "fileTemplates/**")
      include(name: "inspectionDescriptions/**")
      include(name: "intentionDescriptions/**")
      include(name: "tips/**")
      include(name: "search/**")
    }

    buildContext.ant.patternset(id: RESOURCES_EXCLUDED) {
      exclude(name: "**/*Bundle*.properties")
      exclude(name: "**/*Messages.properties")
      exclude(name: "messages/**/*.properties")
      exclude(name: "fileTemplates/**")
      exclude(name: "fileTemplates")
      exclude(name: "inspectionDescriptions/**")
      exclude(name: "inspectionDescriptions")
      exclude(name: "intentionDescriptions/**")
      exclude(name: "intentionDescriptions")
      exclude(name: "tips/**")
      exclude(name: "tips")
      exclude(name: "search/**")
      exclude(name: "**/icon-robots.txt")
    }

    def productLayout = buildContext.productProperties.productLayout
    def enabledPluginModules = getEnabledPluginModules()
    buildContext.messages.debug("Collecting project libraries used by plugins: ")
    List<JpsLibrary> projectLibrariesUsedByPlugins = getPluginsByModules(buildContext, enabledPluginModules).collectMany { plugin ->
      plugin.getActualModules(enabledPluginModules).values().collectMany {
        def module = buildContext.findRequiredModule(it)
        def libraries =
          JpsJavaExtensionService.dependencies(module).includedIn(JpsJavaClasspathKind.PRODUCTION_RUNTIME).libraries.findAll { library ->
            !(library.createReference().parentReference instanceof JpsModuleReference) && !plugin.includedProjectLibraries.any {
              it.libraryName == library.name && it.relativeOutputPath == ""
            }
          }
        if (!libraries.isEmpty()) {
          buildContext.messages.debug(" plugin '$plugin.mainModule', module '$it': ${libraries.collect { "'$it.name'" }.join(",")}")
        }
        libraries
      }
    }

    Set<String> allProductDependencies = (
      productLayout.getIncludedPluginModules(enabledPluginModules) + getIncludedPlatformModules(productLayout)).
      collectMany(new LinkedHashSet<String>()) {
        JpsJavaExtensionService.dependencies(buildContext.findRequiredModule(it)).productionOnly().getModules().collect { it.name }
      }

    platform = PlatformLayout.platform(productLayout.platformLayoutCustomizer) {
      productLayout.additionalPlatformJars.entrySet().each {
        def jarName = it.key
        it.value.each {
          withModule(it, jarName)
        }
      }
      getPlatformApiModules(productLayout).each {
        withModule(it, "platform-api.jar")
      }
      getPlatformImplModules(productLayout).each {
        withModule(it, "platform-impl.jar")
      }
      getProductApiModules(productLayout).each {
        withModule(it, "openapi.jar")
      }

      Map<String, Boolean> usedCommunityModules = new HashMap<>()
      for (name in CommunityRepositoryModules.JAVA_IDE_IMPLEMENTATION_MODULES) {
        usedCommunityModules.put(name, false)
      }
      getProductImplModules(productLayout).each {
        if (usedCommunityModules.containsKey(it)) {
          usedCommunityModules.put(it, true)
        }
        else {
          withModule(it, productLayout.mainJarName)
        }
      }
      usedCommunityModules.forEach({ name, isUsed ->
        if (isUsed) {
          withModule(name)
        }
      })

      productLayout.moduleExcludes.entrySet().each {
        layout.moduleExcludes.putAll(it.key, it.value)
      }
      withModule("intellij.platform.util")
      withModule("intellij.platform.util.rt", "util.jar")
      withModule("intellij.platform.util.classLoader", "util.jar")
      withModule("intellij.platform.util.ui")
      withModule("intellij.platform.util.ex")

      withModule("intellij.platform.diagnostic")
      withModule("intellij.platform.ide.util.io")

      withModule("intellij.platform.concurrency")
      withModule("intellij.platform.core.ui")

      withModule("intellij.platform.builtInServer.impl")
      withModule("intellij.platform.credentialStore")
      withModule("intellij.json")
      withModule("intellij.spellchecker")
      withModule("intellij.platform.images")

      withModule("intellij.relaxng", "intellij-xml.jar")
      withModule("intellij.xml.analysis.impl", "intellij-xml.jar")
      withModule("intellij.xml.psi.impl", "intellij-xml.jar")
      withModule("intellij.xml.structureView.impl", "intellij-xml.jar")
      withModule("intellij.xml.impl", "intellij-xml.jar")

      withModule("intellij.platform.vcs.impl", "intellij-dvcs.jar")
      withModule("intellij.platform.vcs.dvcs.impl", "intellij-dvcs.jar")
      withModule("intellij.platform.vcs.log.graph.impl", "intellij-dvcs.jar")
      withModule("intellij.platform.vcs.log.impl", "intellij-dvcs.jar")

      withModule("intellij.platform.objectSerializer.annotations")
      withModule("intellij.platform.objectSerializer")
      withModule("intellij.platform.configurationStore.impl")

      withModule("intellij.platform.extensions")
      withModule("intellij.platform.serviceContainer")
      withModule("intellij.platform.bootstrap")
      withModule("intellij.java.guiForms.rt")
      withModule("intellij.platform.icons")
      withModule("intellij.platform.boot", "bootstrap.jar")
      withModule("intellij.platform.resources", "resources.jar")
      withModule("intellij.platform.colorSchemes", "resources.jar")
      withModule("intellij.platform.resources.en", "resources.jar")
      withModule("intellij.platform.jps.model.serialization", "jps-model.jar")
      withModule("intellij.platform.jps.model.impl", "jps-model.jar")

      withModule("intellij.platform.externalSystem.rt", "external-system-rt.jar")

      withModule("intellij.platform.cdsAgent", "cds/classesLogAgent.jar")

      if (allProductDependencies.contains("intellij.platform.coverage")) {
        withModule("intellij.platform.coverage")
      }

      projectLibrariesUsedByPlugins.each {
        if (!productLayout.projectLibrariesToUnpackIntoMainJar.contains(it.name) && !layout.excludedProjectLibraries.contains(it.name)) {
          withProjectLibrary(it.name)
        }
      }
      productLayout.projectLibrariesToUnpackIntoMainJar.each {
        withProjectLibraryUnpackedIntoJar(it, productLayout.mainJarName)
      }
      withProjectLibrariesFromIncludedModules(buildContext)

      for (def toRemoveVersion : getLibsToRemoveVersion()) {
        removeVersionFromProjectLibraryJarNames(toRemoveVersion)
      }
    }
  }

  private static Set<String> getLibsToRemoveVersion() {
    return ["Trove4j", "Log4J", "jna", "jetbrains-annotations-java5", "JDOM"].toSet()
  }

  private Set<String> getEnabledPluginModules() {
    buildContext.productProperties.productLayout.allBundledPluginsModules + pluginsToPublish.keySet().
      collect { it.mainModule } as Set<String>
  }

  List<String> getPlatformModules() {
    (platform.moduleJars.values() as List<String>) + toolModules
  }

  static List<String> getIncludedPlatformModules(ProductModulesLayout modulesLayout) {
    getPlatformApiModules(modulesLayout) + getPlatformImplModules(modulesLayout) + getProductApiModules(modulesLayout) +
    getProductImplModules(modulesLayout) + modulesLayout.additionalPlatformJars.values()
  }

  /**
   * @return module names which are required to run necessary tools from build scripts
   */
  static List<String> getToolModules() {
    ["intellij.java.rt", "intellij.platform.main", /*required to build searchable options index*/ "intellij.platform.updater"]
  }

  static List<String> getPlatformApiModules(ProductModulesLayout productLayout) {
    productLayout.platformApiModules.isEmpty() ? CommunityRepositoryModules.PLATFORM_API_MODULES : []
  }

  static List<String> getPlatformImplModules(ProductModulesLayout productLayout) {
    productLayout.platformImplementationModules.isEmpty() ? CommunityRepositoryModules.PLATFORM_IMPLEMENTATION_MODULES : []
  }

  static List<String> getProductApiModules(ProductModulesLayout productLayout) {
    productLayout.platformApiModules.isEmpty() ? productLayout.productApiModules : productLayout.platformApiModules
  }

  static List<String> getProductImplModules(ProductModulesLayout productLayout) {
    productLayout.platformImplementationModules.isEmpty() ? productLayout.productImplementationModules : productLayout.platformImplementationModules
  }

  Collection<String> getIncludedProjectArtifacts() {
    platform.includedArtifacts.keySet() + getPluginsByModules(buildContext, getEnabledPluginModules()).collectMany {it.includedArtifacts.keySet()}
  }

  void buildJARs() {
    prebuildSVG()
    buildOrderFiles()
    buildSearchableOptions()
    buildLib()
    buildBundledPlugins()
    buildOsSpecificBundledPlugins()
    buildNonBundledPlugins()
    buildThirdPartyLibrariesList()
    reorderJARs()
  }

  void reorderJARs() {
    if (!buildContext.options.buildStepsToSkip.contains(BuildOptions.GENERATE_JAR_ORDER_STEP)) {
      def explicitOrderFile = buildContext.productProperties.productLayout.classesLoadingOrderFilePath
      def loadingOrderFilePath = explicitOrderFile != null ? explicitOrderFile : "$buildContext.paths.temp/jarOrder/order.txt"

      if (loadingOrderFilePath != null && new File(loadingOrderFilePath).exists()) {
        reorderJARs(loadingOrderFilePath)
      }
    }
  }

  void prebuildSVG() {
    def productLayout = buildContext.productProperties.productLayout
    SVGPreBuilder.prebuildSVGIcons(buildContext, productLayout.mainModules + getModulesToCompile(buildContext) + modulesForPluginsToPublish)
  }

  /**
   * Creates files with modules and class loading order.
   * The files are used in {@link #processOrderFiles} for creating the "classpath-order.txt" and "order.txt"
   */
  void buildOrderFiles() {
    buildContext.executeStep("Build jar order file", BuildOptions.GENERATE_JAR_ORDER_STEP, {
      def directory = "$buildContext.paths.temp/jarOrder"
      def modulesOrder = "$directory/modules-order.txt"
      def classesOrder = "$directory/classes-order.txt"
      List<String> modulesToIndex =  buildContext.productProperties.productLayout.mainModules + getModulesToCompile(buildContext)
      buildContext.messages.progress("Generating jar loading order for ${modulesToIndex.size()} modules")
      FileUtil.delete(new File(modulesOrder))
      FileUtil.delete(new File(classesOrder))
      BuildTasksImpl.runApplicationStarter(buildContext, directory, modulesToIndex,
                                           ['jarOrder', modulesOrder, classesOrder],
                                           ["idea.log.classpath.info": true])
    })
  }

  /**
   * Build index which is used to search options in the Settings dialog.
   */
  void buildSearchableOptions() {
    buildContext.executeStep("Build searchable options index", BuildOptions.SEARCHABLE_OPTIONS_INDEX_STEP, {
      def productLayout = buildContext.productProperties.productLayout
      def modulesToIndex = productLayout.mainModules + getModulesToCompile(buildContext) + modulesForPluginsToPublish
      modulesToIndex -= "intellij.clion.plugin" // TODO [AK] temporary solution to fix CLion build
      def targetDirectory = getSearchableOptionsDir()
      buildContext.messages.progress("Building searchable options for ${modulesToIndex.size()} modules")
      buildContext.messages.debug("Searchable options are going to be built for the following modules: $modulesToIndex")
      String targetFile = targetDirectory.absolutePath
      FileUtil.delete(targetDirectory)
      // Start the product in headless mode using com.intellij.ide.ui.search.TraverseUIStarter.
      // It'll process all UI elements in Settings dialog and build index for them.
      BuildTasksImpl.runApplicationStarter(buildContext, "$buildContext.paths.temp/searchableOptions", modulesToIndex, ['traverseUI', targetFile, 'true'])
      def modules = targetDirectory.list()
      if (modules == null || modules.length == 0) {
        buildContext.messages.error("Failed to build searchable options index: $targetFile is empty")
      }
      else {
        buildContext.messages.info("Searchable options are built successfully for $modules.length modules")
        buildContext.messages.debug("The following modules contain searchable options: $modules")
      }
    })
  }

  static List<String> getModulesToCompile(BuildContext buildContext) {
    def productLayout = buildContext.productProperties.productLayout
    productLayout.getIncludedPluginModules(productLayout.allBundledPluginsModules) +
    getPlatformApiModules(productLayout) +
    getPlatformImplModules(productLayout) +
    getProductApiModules(productLayout) +
    getProductImplModules(productLayout) +
    productLayout.additionalPlatformJars.values() +
    toolModules + buildContext.productProperties.additionalModulesToCompile +
    SVGPreBuilder.getModulesToInclude()
  }

  List<String> getModulesForPluginsToPublish() {
    def enabledModulesSet = enabledPluginModules
    platformModules + (pluginsToPublish.collect { it.key.getActualModules(enabledModulesSet).values() }.flatten() as List<String>)
  }

  void reorderJARs(String loadingOrderFilePath) {
    buildContext.messages.block("Reorder JARs") {
      String targetDirectory = buildContext.paths.distAll
      buildContext.messages.progress("Reordering *.jar files in $targetDirectory")
      File ignoredJarsFile = new File(buildContext.paths.temp, "reorder-jars/required_for_dist.txt")
      ignoredJarsFile.parentFile.mkdirs()
      def moduleJars = platform.moduleJars.entrySet().collect(new HashSet()) { getActualModuleJarPath(it.key, it.value, platform.explicitlySetJarPaths) }
      ignoredJarsFile.text = new File(buildContext.paths.distAll, "lib").list()
        .findAll {it.endsWith(".jar") && !moduleJars.contains(it)}
        .join("\n")

      buildContext.ant.java(classname: "com.intellij.util.io.zip.ReorderJarsMain", fork: true, failonerror: true) {
        arg(value: loadingOrderFilePath)
        arg(value: targetDirectory)
        arg(value: targetDirectory)
        arg(value: ignoredJarsFile.parent)
        classpath {
          buildContext.getModuleRuntimeClasspath(buildContext.findRequiredModule("intellij.platform.util"), false).each {
            pathelement(location: it)
          }
        }
      }
    }
  }

  void buildAdditionalArtifacts() {
    def productProperties = buildContext.productProperties

    if (productProperties.generateLibrariesLicensesTable && !buildContext.options.buildStepsToSkip.
      contains(BuildOptions.THIRD_PARTY_LIBRARIES_LIST_STEP)) {
      String artifactNamePrefix = productProperties.getBaseArtifactName(buildContext.applicationInfo, buildContext.buildNumber)
      buildContext.ant.copy(file: getThirdPartyLibrariesHtmlFilePath(),
                            tofile: "$buildContext.paths.artifacts/$artifactNamePrefix-third-party-libraries.html")
      buildContext.ant.copy(file: getThirdPartyLibrariesJsonFilePath(),
                            tofile: "$buildContext.paths.artifacts/$artifactNamePrefix-third-party-libraries.json")
    }

    buildInternalUtilities()

    if (productProperties.buildSourcesArchive) {
      def archiveName = "${productProperties.getBaseArtifactName(buildContext.applicationInfo, buildContext.buildNumber)}-sources.zip"
      BuildTasks.create(buildContext).zipSourcesOfModules(usedModules, "$buildContext.paths.artifacts/$archiveName")
    }
  }

  void buildInternalUtilities() {
    if (buildContext.productProperties.scrambleMainJar) {
      createLayoutBuilder().layout("$buildContext.paths.buildOutputRoot/internal") {
        jar("internalUtilities.jar") {
          module("intellij.tools.internalUtilities")
        }
      }
    }
  }

  private void buildThirdPartyLibrariesList() {
    buildContext.executeStep("Generate table of licenses for used third-party libraries", BuildOptions.THIRD_PARTY_LIBRARIES_LIST_STEP) {
      def generator = LibraryLicensesListGenerator.create(buildContext.messages,
                                                          buildContext.project,
                                                          buildContext.productProperties.allLibraryLicenses,
                                                          usedModules)
      generator.generateHtml(getThirdPartyLibrariesHtmlFilePath())
      generator.generateJson(getThirdPartyLibrariesJsonFilePath())
    }
  }

  private String getThirdPartyLibrariesHtmlFilePath() {
    "$buildContext.paths.distAll/$THIRD_PARTY_LIBRARIES_FILE_PATH"
  }

  private String getThirdPartyLibrariesJsonFilePath() {
    "$buildContext.paths.temp/third-party-libraries.json"
  }


  /**
   * Post processing after {@link #buildOrderFiles}
   */
  private void processOrderFiles(LayoutBuilder layoutBuilder) {
    if (!buildContext.options.buildStepsToSkip.contains(BuildOptions.GENERATE_JAR_ORDER_STEP)) {
      buildContext.messages.info("Start processing order files")
      def libModulesToJar = getModuleToJarMap(platform)
      Map<String, String> pluginsToJar = getPluginModulesToJar()
      Map<String, String> pathToToJarName = getLibraryPathToJarName()
      Map<String, String> pathToModuleName = getModulePathToModuleName(libModulesToJar.keySet() + pluginsToJar.keySet())

      addClassesOrderFile(pathToModuleName, pathToToJarName, pluginsToJar, libModulesToJar)
      addJarOrderFile(layoutBuilder, pathToModuleName, pathToToJarName, libModulesToJar)
      buildContext.messages.info("End processing order files")
    }
  }

  private void addClassesOrderFile(Map<String, String> pathToModuleName,
                                   Map<String, String> pathToToJarName,
                                   Map<String, String> pluginModulesToJar,
                                   Map<String, String> libModulesToJar) {
    def jarOrderTempDirectoryPath = "$buildContext.paths.temp/jarOrder"
    def classesLoadingOrderFilePath = "$jarOrderTempDirectoryPath/classes-order.txt"
    def finalOrder = "$jarOrderTempDirectoryPath/order.txt"
    def classesFile = new File(classesLoadingOrderFilePath)
    if (!classesFile.exists()) {
      buildContext.messages.info("Failed to generate classes order file: $classesLoadingOrderFilePath doesn't exist")
      return
    }
    def lines = classesFile.readLines()
    if (lines.isEmpty()) {
      buildContext.messages.info("Failed to generate classes order file: $classesLoadingOrderFilePath empty")
      return
    }

    def resultLines = new ArrayList<String>()
    for (def line : lines) {
      List<String> split = StringUtil.split(line, ":")
      if (!(split.size() == 2)) continue
      String modulePath = split.get(1)
      if (modulePath.endsWith(".jar")) {
        String jarName = pathToToJarName.get(modulePath)
        //possible jar from a plugin
        if (jarName == null) continue
        resultLines.add(split.get(0) + ":/lib/" + jarName)
      }
      else {
        def moduleName = pathToModuleName.get(modulePath)
        if (moduleName == null) continue
        def libJarName = libModulesToJar.get(moduleName)
        if (libJarName != null) {
          resultLines.add(split.get(0) + ":/lib/" + libJarName)
        }
        else {
          def moduleJarName = pluginModulesToJar.get(moduleName)
          if (moduleName == null) continue
          resultLines.add("${split.get(0)}:$moduleJarName")
        }
      }
    }
    def resultFile = new File(finalOrder)
    FileUtil.writeToFile(resultFile, resultLines.join("\n"))
    buildContext.messages.info("Completed generating classes order file. Before preparing: ${lines.size()} after: ${resultLines.size()}")
  }

  private Map<String, String> getPluginModulesToJar() {
    def pluginsToJar = new HashMap<String, String>()
    def productLayout = buildContext.productProperties.productLayout
    def allPlugins = getPluginsByModules(buildContext, productLayout.bundledPluginModules + productLayout.pluginModulesToPublish)
    for (def plugin : allPlugins) {
      def directory = getActualPluginDirectoryName(plugin, buildContext)
      getModuleToJarMap(plugin, pluginsToJar, "$PLUGINS_DIRECTORY/$directory/lib/")
    }
    return pluginsToJar
  }

  private Map<String, String> getModulePathToModuleName(Set<String> allModules) {
    def pathToModuleName = new HashMap<String, String>()
    for (def moduleName in allModules) {
      def module = buildContext.findModule(moduleName)
      if (module == null) continue
      def classpath = buildContext.getModuleOutputPath(module)
      pathToModuleName.put(classpath, moduleName)
    }
    return pathToModuleName
  }

  private Map<String, String> getLibraryPathToJarName() {
    def libWithoutVersion = new HashSet(platform.projectLibrariesWithRemovedVersionFromJarNames)
    def libraryJarPathToJarName = new HashMap()
    buildContext.project.libraryCollection.libraries.each {
      def name = it.getName()
      for (def libFile : it.getFiles(JpsOrderRootType.COMPILED)) {
        def fileName = libFile.getName()
        def jarName = fileName
        if (libWithoutVersion.contains(name)) {
          def candidate = getLibraryNameWithoutVersion(libFile)
          if (candidate != null) {
            jarName = candidate
          }
        }
        libraryJarPathToJarName.put(libFile.getPath(), jarName)
      }
    }
    return libraryJarPathToJarName
  }

  private void addJarOrderFile(LayoutBuilder layoutBuilder,
                               Map<String, String> pathToModuleName,
                               Map<String, String> pathToToJarName,
                               Map<String, String> libModulesToJar) {
    def jarOrderTempDirectoryPath = "$buildContext.paths.temp/jarOrder"
    def modulesLoadingOrderFilePath = "$jarOrderTempDirectoryPath/modules-order.txt"
    def file = new File(modulesLoadingOrderFilePath)
    if (!file.exists()) {
      buildContext.messages.info("Failed to generate jar loading order file: $modulesLoadingOrderFilePath doesn't exist")
    }

    def lines = FileUtil.loadLines(file)

    def jarFileNames = new LinkedHashSet()
    for (def line : lines) {
      def jarName
      if (line.endsWith(".jar")) {
        jarName = pathToToJarName.get(line)
      }
      else {
        def moduleName = pathToModuleName.get(line)
        jarName = moduleName != null ? libModulesToJar.get(moduleName) : null
      }
      if (jarName != null) jarFileNames.add(jarName)
    }

    if (jarFileNames.isEmpty()) {
      buildContext.messages.warning("Jar order file is empty")
      return
    }
    def bootstrap = "intellij.platform.bootstrap"
    def newFile = new File(jarOrderTempDirectoryPath, bootstrap + "/com/intellij/ide/classpath-order.txt")
    FileUtil.writeToFile(newFile, jarFileNames.join("\n"))
    buildContext.messages.info("Completed generating jar file. Before preparing: ${lines.size()} after: ${jarFileNames.size()}")
    layoutBuilder.patchModuleOutput(bootstrap, "$jarOrderTempDirectoryPath/$bootstrap")
    buildContext.messages.info("Add patch to apply jar order:" + newFile.path)
  }

  private Map<String, String> getModuleToJarMap(BaseLayout layout, Map<String, String> moduleToJar = new HashMap<>(), String jarPrefix = "") {
    for (def entry : layout.moduleJars.entrySet()) {
      def jarName = entry.key
      def fixedJarName = getActualModuleJarPath(jarName, entry.value, layout.explicitlySetJarPaths)
      entry.value.forEach({ el -> moduleToJar.put(el, jarPrefix + fixedJarName) })
    }
    return moduleToJar
  }

  private void buildLib() {
    def ant = buildContext.ant
    def layoutBuilder = createLayoutBuilder()
    def productLayout = buildContext.productProperties.productLayout

    processOrderFiles(layoutBuilder)
    addSearchableOptions(layoutBuilder)
    SVGPreBuilder.addGeneratedResources(buildContext, layoutBuilder)

    def applicationInfoFile = FileUtil.toSystemIndependentName(patchedApplicationInfo.absolutePath)
    def applicationInfoDir = "$buildContext.paths.temp/applicationInfo"
    ant.copy(file: applicationInfoFile, todir: "$applicationInfoDir/idea")
    layoutBuilder.patchModuleOutput(buildContext.productProperties.applicationInfoModule, applicationInfoDir)

    if (buildContext.productProperties.reassignAltClickToMultipleCarets) {
      def patchedKeyMapDir = createKeyMapWithAltClickReassignedToMultipleCarets()
      layoutBuilder.patchModuleOutput("intellij.platform.resources", FileUtil.toSystemIndependentName(patchedKeyMapDir.absolutePath))
    }
    if (buildContext.proprietaryBuildTools.featureUsageStatisticsProperties != null) {
      def whiteList = StatisticsRecorderBundledWhiteListProvider.downloadWhiteList(buildContext)
      layoutBuilder.patchModuleOutput('intellij.platform.ide.impl', whiteList.absolutePath)
    }

    buildContext.messages.block("Build platform JARs in lib directory") {
      buildByLayout(layoutBuilder, platform, buildContext.paths.distAll, platform.moduleJars, [])
    }

    if (buildContext.proprietaryBuildTools.scrambleTool != null) {
      def forbiddenJarNames = buildContext.proprietaryBuildTools.scrambleTool.namesOfJarsRequiredToBeScrambled
      def packagedFiles = new File(buildContext.paths.distAll, "lib").listFiles()
      def forbiddenJars = packagedFiles.findAll { forbiddenJarNames.contains(it.name) }
      if (!forbiddenJars.empty) {
        buildContext.messages.error( "The following JARs cannot be included into the product 'lib' directory, they need to be scrambled with the main jar: ${forbiddenJars}")
      }
      def modulesToBeScrambled = buildContext.proprietaryBuildTools.scrambleTool.namesOfModulesRequiredToBeScrambled
      platform.moduleJars.keySet().each { jarName ->
        if (jarName != productLayout.mainJarName) {
          def notScrambled = platform.moduleJars.get(jarName).intersect(modulesToBeScrambled)
          if (!notScrambled.isEmpty()) {
            buildContext.messages.error("Module '${notScrambled.first()}' is included into $jarName which is not scrambled.")
          }
        }
      }
    }

    usedModules.addAll(layoutBuilder.usedModules)
  }

  static String getLibraryNameWithoutVersion(library) {
    def matcher = library.name =~ LayoutBuilder.JAR_NAME_WITH_VERSION_PATTERN
    if (matcher.matches()) {
      return matcher.group(1) + ".jar"
    }

    return null
  }

  private void buildBundledPlugins() {
    def layoutBuilder = createLayoutBuilder()
    def allPlugins = getPluginsByModules(buildContext, buildContext.productProperties.productLayout.bundledPluginModules)
    def pluginDirectoriesToSkip = buildContext.options.bundledPluginDirectoriesToSkip as Set<String>
    buildContext.messages.debug("Plugin directories to skip: " + pluginDirectoriesToSkip)
    buildContext.messages.block("Build bundled plugins") {
      def pluginsToBundle = allPlugins.findAll {
        satisfiesBundlingRequirements(it, null) && !pluginDirectoriesToSkip.contains(it.directoryName)
      }
      buildPlugins(layoutBuilder, pluginsToBundle, "$buildContext.paths.distAll$PLUGINS_DIRECTORY" )
    }
    usedModules.addAll(layoutBuilder.usedModules)
  }

  private boolean satisfiesBundlingRequirements(PluginLayout plugin, @Nullable OsFamily osFamily) {
    def bundlingRestrictions = plugin.bundlingRestrictions
    if (!buildContext.applicationInfo.isEAP && bundlingRestrictions.includeInEapOnly) {
      return false
    }
    osFamily == null ? bundlingRestrictions.supportedOs == OsFamily.ALL
                     : bundlingRestrictions.supportedOs != OsFamily.ALL && bundlingRestrictions.supportedOs.contains(osFamily)
  }

  private void buildOsSpecificBundledPlugins() {
    def productLayout = buildContext.productProperties.productLayout
    for (OsFamily osFamily in OsFamily.values()) {
      List<PluginLayout> osSpecificPlugins =
        getPluginsByModules(buildContext, productLayout.bundledOsPluginModules[osFamily] ?: []) +
        getPluginsByModules(buildContext, productLayout.bundledPluginModules).findAll {
          satisfiesBundlingRequirements(it, osFamily)
        }

      if (!osSpecificPlugins.isEmpty() && buildContext.shouldBuildDistributionForOS(osFamily.osId)) {
        def layoutBuilder = createLayoutBuilder()
        buildContext.messages.block("Build bundled plugins for $osFamily.osName") {
          buildPlugins(layoutBuilder, osSpecificPlugins,
                       "$buildContext.paths.buildOutputRoot/dist.$osFamily.distSuffix/plugins")
        }
        usedModules.addAll(layoutBuilder.usedModules)
      }
    }
  }

  void buildNonBundledPlugins() {
    if (pluginsToPublish.isEmpty()) return

    def productLayout = buildContext.productProperties.productLayout
    def ant = buildContext.ant
    def layoutBuilder = createLayoutBuilder()
    buildContext.executeStep("Build non-bundled plugins", BuildOptions.NON_BUNDLED_PLUGINS_STEP) {
      def pluginXmlFiles = new LinkedHashMap<PluginLayout, String>()

      pluginsToPublish.each { pluginAndPublishing ->
        def plugin = pluginAndPublishing.key

        def moduleOutput = buildContext.getModuleOutputPath(buildContext.findRequiredModule(plugin.mainModule))
        def pluginXmlPath = "$moduleOutput/META-INF/plugin.xml"
        if (!new File(pluginXmlPath)) {
          buildContext.messages.error("plugin.xml not found in $plugin.mainModule module: $pluginXmlPath")
        }

        pluginXmlFiles.put(plugin, pluginXmlPath)
      }

      if (buildContext.productProperties.setPluginAndIDEVersionInPluginXml) {
        pluginsToPublish.each { pluginAndPublishing ->
          def plugin = pluginAndPublishing.key
          def publishingSpec = pluginAndPublishing.value

          def pluginXmlPath = pluginXmlFiles[plugin]
          def patchedPluginXmlDir = "$buildContext.paths.temp/patched-plugin-xml/$plugin.mainModule"
          def patchedPluginXmlPath = "$patchedPluginXmlDir/META-INF/plugin.xml"
          pluginXmlFiles.put(plugin, patchedPluginXmlPath)

          ant.copy(file: pluginXmlPath, todir: "$patchedPluginXmlDir/META-INF")

          CompatibleBuildRange compatibleBuildRange = publishingSpec.compatibleBuildRange
          if (compatibleBuildRange == null) {
            def includeInBuiltinCustomRepository = productLayout.prepareCustomPluginRepositoryForPublishedPlugins &&
                                                   publishingSpec.includeInCustomPluginRepository &&
                                                   buildContext.proprietaryBuildTools.artifactsServer != null
            //plugins included into the built-in custom plugin repository should use EXACT range because such custom repositories are used for nightly builds and there may be API differences between different builds
            compatibleBuildRange = includeInBuiltinCustomRepository ? CompatibleBuildRange.EXACT :
                                   //when publishing plugins with EAP build let's use restricted range to ensure that users will update to a newer version of the plugin when they update to the next EAP or release build
                                   buildContext.applicationInfo.isEAP ? CompatibleBuildRange.RESTRICTED_TO_SAME_RELEASE
                                                                      : CompatibleBuildRange.NEWER_WITH_SAME_BASELINE
          }

          setPluginVersionAndSince(patchedPluginXmlPath, getPluginVersion(plugin),
                                   buildContext.buildNumber,
                                   compatibleBuildRange)
          layoutBuilder.patchModuleOutput(plugin.mainModule, patchedPluginXmlDir)
        }
      }

      def pluginsToPublishDir = "$buildContext.paths.temp/${buildContext.applicationInfo.productCode}-plugins-to-publish"
      def pluginsDirectoryName = "${buildContext.applicationInfo.productCode}-plugins"
      buildPlugins(layoutBuilder, new ArrayList<PluginLayout>(pluginsToPublish.keySet()), pluginsToPublishDir)
      def nonBundledPluginsArtifacts = "$buildContext.paths.artifacts/$pluginsDirectoryName"

      def pluginsToIncludeInCustomRepository = new ArrayList<PluginRepositorySpec>()
      def whiteList = new File("$buildContext.paths.communityHome/../build/plugins-autoupload-whitelist.txt").readLines()
        .stream().map { it.trim() }.filter { !it.isEmpty() && !it.startsWith("//") }.collect(Collectors.toSet())

      pluginsToPublish.each { pluginAndPublishing ->
        def plugin = pluginAndPublishing.key
        def publishingSpec = pluginAndPublishing.value

        def includeInCustomRepository = productLayout.prepareCustomPluginRepositoryForPublishedPlugins && publishingSpec.includeInCustomPluginRepository

        def directory = getActualPluginDirectoryName(plugin, buildContext)
        String suffix = includeInCustomRepository ? "" : "-${getPluginVersion(plugin)}"
        def targetDirectory = publishingSpec.includeIntoDirectoryForAutomaticUploading &&
                              whiteList.contains(plugin.mainModule)
          ? "$nonBundledPluginsArtifacts/auto-uploading"
          : nonBundledPluginsArtifacts
        def destFile = "$targetDirectory/$directory${suffix}.zip"

        if (includeInCustomRepository) {
          pluginsToIncludeInCustomRepository.add(new PluginRepositorySpec(pluginZip: destFile.toString(),
                                                                          pluginXml: pluginXmlFiles[plugin]))
        }

        ant.zip(destfile: destFile) {
          zipfileset(dir: "$pluginsToPublishDir/$directory", prefix: directory)
        }
        buildContext.notifyArtifactBuilt(destFile)
      }

      KeymapPluginsBuilder.buildKeymapPlugins(buildContext, "$nonBundledPluginsArtifacts/auto-uploading")

      if (productLayout.prepareCustomPluginRepositoryForPublishedPlugins) {
        new PluginRepositoryXmlGenerator(buildContext).generate(pluginsToIncludeInCustomRepository, nonBundledPluginsArtifacts)
        buildContext.notifyArtifactBuilt("$nonBundledPluginsArtifacts/plugins.xml")
      }
    }
  }

  private String getPluginVersion(PluginLayout plugin) {
    return plugin.versionEvaluator.apply(buildContext)
  }

  /**
   * Returns name of directory in the product distribution where plugin will be placed. For plugins which use the main module name as the
   * directory name return the old module name to temporary keep layout of plugins unchanged.
   */
  static String getActualPluginDirectoryName(PluginLayout plugin, BuildContext context) {
    if (!plugin.directoryNameSetExplicitly && plugin.directoryName == BaseLayout.convertModuleNameToFileName(plugin.mainModule)
                                           && context.getOldModuleName(plugin.mainModule) != null) {
      context.getOldModuleName(plugin.mainModule)
    }
    else {
      plugin.directoryName
    }
  }

  static List<PluginLayout> getPluginsByModules(BuildContext buildContext, Collection<String> modules) {
    def allNonTrivialPlugins = buildContext.productProperties.productLayout.allNonTrivialPlugins
    def allOptionalModules = allNonTrivialPlugins.collectMany {it.optionalModules}
    def nonTrivialPlugins = allNonTrivialPlugins.groupBy { it.mainModule }
    (modules - allOptionalModules).collect { (nonTrivialPlugins[it] ?: nonTrivialPlugins[buildContext.findModule(it)?.name])?.first() ?: PluginLayout.plugin(it) }
  }

  private void buildPlugins(LayoutBuilder layoutBuilder, List<PluginLayout> pluginsToInclude, String targetDirectory) {
    addSearchableOptions(layoutBuilder)
    def enabledModulesSet = enabledPluginModules
    pluginsToInclude.each { plugin ->
      def actualModuleJars = plugin.getActualModules(enabledModulesSet)
      checkOutputOfPluginModules(plugin.mainModule, actualModuleJars.values(), plugin.moduleExcludes)
      List<Pair<File, String>> generatedResources = plugin.resourceGenerators.collectMany {
        File resourceFile = it.first.generateResources(buildContext)
        resourceFile != null ? [Pair.create(resourceFile, it.second)] : []
      }
      buildByLayout(layoutBuilder, plugin, "$targetDirectory/${getActualPluginDirectoryName(plugin, buildContext)}", actualModuleJars, generatedResources)
    }
  }

  private void addSearchableOptions(LayoutBuilder layoutBuilder) {
    if (!buildContext.options.buildStepsToSkip.contains(BuildOptions.SEARCHABLE_OPTIONS_INDEX_STEP)) {
      def searchableOptionsDir = getSearchableOptionsDir()
      if (!searchableOptionsDir.exists()) {
        buildContext.messages.error("There are no searchable options available. " +
                                    "Please ensure that you call DistributionJARsBuilder#buildSearchableOptions before this method.")
      }
      searchableOptionsDir.eachFile(FileType.DIRECTORIES) {
        layoutBuilder.patchModuleOutput(it.name, FileUtil.toSystemIndependentName(it.absolutePath))
      }
    }
  }

  private File getSearchableOptionsDir() {
    new File(buildContext.paths.temp, "searchableOptions/result")
  }

  private void checkOutputOfPluginModules(String mainPluginModule, Collection<String> moduleNames, MultiValuesMap<String, String> moduleExcludes) {
    def modulesWithPluginXml = moduleNames.findAll { containsFileInOutput(it, "META-INF/plugin.xml", moduleExcludes.get(it)) }
    if (modulesWithPluginXml.size() > 1) {
      buildContext.messages.error("Multiple modules (${modulesWithPluginXml.join(", ")}) from '$mainPluginModule' plugin contain plugin.xml files so the plugin won't work properly")
    }

    moduleNames.each {
      if (it != "intellij.java.guiForms.rt" && containsFileInOutput(it, "com/intellij/uiDesigner/core/GridLayoutManager.class", moduleExcludes.get(it))) {
        buildContext.messages.error("Runtime classes of GUI designer must not be packaged to '$it' module in '$mainPluginModule' plugin, because they are included into a platform JAR. " +
                                    "Make sure that 'Automatically copy form runtime classes to the output directory' is disabled in Settings | Editor | GUI Designer.")
      }
    }
  }

  private boolean containsFileInOutput(String moduleName, String filePath, Collection<String> excludes) {
    def moduleOutput = new File(buildContext.getModuleOutputPath(buildContext.findRequiredModule(moduleName)))
    def fileInOutput = new File(moduleOutput, filePath)
    return fileInOutput.exists() && (excludes == null || excludes.every {
      createFileSet(it, moduleOutput).iterator().every { !(it instanceof FileProvider && FileUtil.filesEqual(it.file, fileInOutput))}
    })
  }

  /**
   * Returns path to a JAR file in the product distribution where platform/plugin classes will be placed. If the JAR name corresponds to
   * a module name and the module was renamed, return the old name to temporary keep the product layout unchanged.
   */
  private String getActualModuleJarPath(String relativeJarPath, Collection<String> moduleNames, Set<String> explicitlySetJarPaths) {
    if (explicitlySetJarPaths.contains(relativeJarPath)) {
      return relativeJarPath
    }
    for (String moduleName : moduleNames) {
      if (relativeJarPath == "${BaseLayout.convertModuleNameToFileName(moduleName)}.jar" &&
          buildContext.getOldModuleName(moduleName) !=
          null) {
        return "${buildContext.getOldModuleName(moduleName)}.jar"
      }
    }
    return relativeJarPath
  }

  /**
   * @param moduleJars mapping from JAR path relative to 'lib' directory to names of modules
   * @param additionalResources pairs of resources files and corresponding relative output paths
   */
  private void buildByLayout(LayoutBuilder layoutBuilder, BaseLayout layout, String targetDirectory, MultiValuesMap<String, String> moduleJars,
                             List<Pair<File, String>> additionalResources) {
    def ant = buildContext.ant
    def resourceExcluded = RESOURCES_EXCLUDED
    def resourcesIncluded = RESOURCES_INCLUDED
    def buildContext = buildContext
    checkModuleExcludes(layout.moduleExcludes)
    MultiValuesMap<String, String> actualModuleJars = new MultiValuesMap<>(true)
    moduleJars.entrySet().each {
      def modules = it.value
      def jarPath = getActualModuleJarPath(it.key, modules, layout.explicitlySetJarPaths)
      actualModuleJars.putAll(jarPath, modules)
    }
    layoutBuilder.layout(targetDirectory) {
      dir("lib") {
        actualModuleJars.entrySet().each {
          def modules = it.value
          def jarPath = it.key
          jar(jarPath, true) {
            modules.each { moduleName ->
              modulePatches([moduleName]) {
                if (layout.localizableResourcesJarName(moduleName) != null) {
                  ant.patternset(refid: resourceExcluded)
                }
              }
              module(moduleName) {
                if (layout.localizableResourcesJarName(moduleName) != null) {
                  ant.patternset(refid: resourceExcluded)
                }
                else {
                  ant.exclude(name: "**/icon-robots.txt")
                }

                layout.moduleExcludes.get(moduleName)?.each {
                  //noinspection GrUnresolvedAccess
                  ant.exclude(name: it)
                }
              }
            }
            layout.projectLibrariesToUnpack.get(jarPath)?.each {
              buildContext.project.libraryCollection.findLibrary(it)?.getFiles(JpsOrderRootType.COMPILED)?.each {
                ant.zipfileset(src: it.absolutePath)
              }
            }
          }
        }
        def outputResourceJars = new MultiValuesMap<String, String>(true)
        actualModuleJars.values().forEach {
          def resourcesJarName = layout.localizableResourcesJarName(it)
          if (resourcesJarName != null) {
            outputResourceJars.put(resourcesJarName, it)
          }
        }
        if (!outputResourceJars.empty) {
          outputResourceJars.keySet().forEach { resourceJarName ->
            jar(resourceJarName, true) {
              outputResourceJars.get(resourceJarName).each { moduleName ->
                modulePatches([moduleName]) {
                  ant.patternset(refid: resourcesIncluded)
                }
                module(moduleName) {
                  layout.moduleExcludes.get(moduleName)?.each {
                    //noinspection GrUnresolvedAccess
                    ant.exclude(name: "$it/**")
                  }
                  ant.patternset(refid: resourcesIncluded)
                }
              }
            }
          }
        }
        layout.includedProjectLibraries.each { libraryData ->
          dir(libraryData.relativeOutputPath) {
            projectLibrary(libraryData.libraryName, layout instanceof PlatformLayout && layout.projectLibrariesWithRemovedVersionFromJarNames.contains(libraryData.libraryName))
          }
        }
        layout.includedArtifacts.entrySet().each {
          def artifactName = it.key
          def relativePath = it.value
          dir(relativePath) {
            artifact(artifactName)
          }
        }

        //include all module libraries from the plugin modules added to IDE classpath to layout
        actualModuleJars.entrySet().findAll { !it.key.contains("/") }.collectMany { it.value }
                             .findAll {!layout.modulesWithExcludedModuleLibraries.contains(it)}.each { moduleName ->
          findModule(moduleName).dependenciesList.dependencies.
            findAll { it instanceof JpsLibraryDependency && it?.libraryReference?.parentReference?.resolve() instanceof JpsModule }.
            findAll { JpsJavaExtensionService.instance.getDependencyExtension(it)?.scope?.isIncludedIn(JpsJavaClasspathKind.PRODUCTION_RUNTIME) ?: false }.
            each {
              jpsLibrary(((JpsLibraryDependency)it).library)
            }
        }

        layout.includedModuleLibraries.each { data ->
          dir(data.relativeOutputPath) {
            moduleLibrary(data.moduleName, data.libraryName)
          }
        }
      }
      layout.resourcePaths.each {
        def path = FileUtil.toSystemIndependentName(new File("${basePath(buildContext, it.moduleName)}/$it.resourcePath").absolutePath)
        if (it.packToZip) {
          zip(it.relativeOutputPath) {
            if (new File(path).isFile()) {
              ant.fileset(file: path)
            }
            else {
              ant.fileset(dir: path)
            }
          }
        }
        else {
          dir(it.relativeOutputPath) {
            if (new File(path).isFile()) {
              ant.fileset(file: path)
            }
            else {
              ant.fileset(dir: path)
            }
          }
        }
      }
      additionalResources.each {
        File resource = it.first
        dir(it.second) {
          if (resource.isFile()) {
            ant.fileset(file: resource.absolutePath)
          }
          else {
            ant.fileset(dir: resource.absolutePath)
          }
        }
      }
    }
  }

  private void checkModuleExcludes(MultiValuesMap<String, String> moduleExcludes) {
    moduleExcludes.entrySet().each { entry ->
      String module = entry.key
      entry.value.each { pattern ->
        def moduleOutput = new File(buildContext.getModuleOutputPath(buildContext.findRequiredModule(module)))
        if (!moduleOutput.exists()) {
          buildContext.messages.error("There are excludes defined for module '$module', but the module wasn't compiled; " +
                                      "most probably it means that '$module' isn't include into the product distribution so it makes no sense to define excludes for it.")
        }
        if (createFileSet(pattern, moduleOutput).size() == 0) {
          buildContext.messages.error("Incorrect excludes for module '$module': nothing matches to $pattern in the module output at $moduleOutput")
        }
      }
    }
  }

  private FileSet createFileSet(String pattern, File baseDir) {
    def fileSet = new FileSet()
    fileSet.setProject(buildContext.ant.antProject)
    fileSet.setDir(baseDir)
    fileSet.createInclude().setName(pattern)
    return fileSet
  }

  static String basePath(BuildContext buildContext, String moduleName) {
    JpsPathUtil.urlToPath(buildContext.findRequiredModule(moduleName).contentRootsList.urls.first())
  }

  private LayoutBuilder createLayoutBuilder() {
    new LayoutBuilder(buildContext, COMPRESS_JARS)
  }

  private void setPluginVersionAndSince(String pluginXmlPath, String version, String buildNumber,
                                        CompatibleBuildRange compatibleBuildRange) {
    buildContext.ant.replaceregexp(file: pluginXmlPath,
                                   match: "<version>[\\d.]*</version>",
                                   replace: "<version>${version}</version>")
    def sinceBuild
    def untilBuild
    if (compatibleBuildRange != CompatibleBuildRange.EXACT && buildNumber.matches(/(\d+\.)+\d+/)) {
      if (compatibleBuildRange == CompatibleBuildRange.ANY_WITH_SAME_BASELINE) {
        sinceBuild = buildNumber.substring(0, buildNumber.indexOf('.'))
        untilBuild = buildNumber.substring(0, buildNumber.indexOf('.')) + ".*"
      }
      else {
        if (buildNumber.matches(/\d+\.\d+/)) {
          sinceBuild = buildNumber
        }
        else {
          sinceBuild = buildNumber.substring(0, buildNumber.lastIndexOf('.'))
        }
        int end = compatibleBuildRange == CompatibleBuildRange.RESTRICTED_TO_SAME_RELEASE ? buildNumber.lastIndexOf('.') :
                  buildNumber.indexOf('.')
        untilBuild = buildNumber.substring(0, end) + ".*"
      }
    }
    else {
      sinceBuild = buildNumber
      untilBuild = buildNumber
    }
    buildContext.ant.replaceregexp(file: pluginXmlPath,
                                   match: "<idea-version\\s*since-build=\"\\d+\\.\\d+\"\\s*until-build=\"\\d+\\.\\d+\"",
                                   replace: "<idea-version since-build=\"${sinceBuild}\" until-build=\"${untilBuild}\"")
    buildContext.ant.replaceregexp(file: pluginXmlPath,
                                   match: "<idea-version\\s*since-build=\"\\d+\\.\\d+\"",
                                   replace: "<idea-version since-build=\"${sinceBuild}\"")
    buildContext.ant.replaceregexp(file: pluginXmlPath,
                                   match: "<change-notes>\\s*<\\!\\[CDATA\\[\\s*Plugin version: \\\$\\{version\\}",
                                   replace: "<change-notes>\n<![CDATA[\nPlugin version: ${version}")
    def file = new File(pluginXmlPath)
    def text = file.text
    def anchor = text.contains("</id>") ? "</id>" : "</name>"
    if (!text.contains("<version>")) {
      file.text = text.replace(anchor, "${anchor}\n  <version>${version}</version>")
      text = file.text
    }
    if (!text.contains("<idea-version since-build")) {
      file.text = text.replace(anchor, "${anchor}\n  <idea-version since-build=\"${sinceBuild}\" until-build=\"${untilBuild}\"/>")
    }
  }

  private File createKeyMapWithAltClickReassignedToMultipleCarets() {
    def sourceFile = new File("${buildContext.getModuleOutputPath(buildContext.findModule("intellij.platform.resources"))}/keymaps/\$default.xml")
    String defaultKeymapContent = sourceFile.text
    defaultKeymapContent = defaultKeymapContent.replace("<mouse-shortcut keystroke=\"alt button1\"/>",
                                                        "<mouse-shortcut keystroke=\"to be alt shift button1\"/>")
    defaultKeymapContent = defaultKeymapContent.replace("<mouse-shortcut keystroke=\"alt shift button1\"/>",
                                                        "<mouse-shortcut keystroke=\"alt button1\"/>")
    defaultKeymapContent = defaultKeymapContent.replace("<mouse-shortcut keystroke=\"to be alt shift button1\"/>",
                                                        "<mouse-shortcut keystroke=\"alt shift button1\"/>")
    def patchedKeyMapDir = new File(buildContext.paths.temp, "patched-keymap")
    def targetFile = new File(patchedKeyMapDir, "keymaps/\$default.xml")
    FileUtil.createParentDirs(targetFile)
    targetFile.text = defaultKeymapContent
    return patchedKeyMapDir
  }
}
