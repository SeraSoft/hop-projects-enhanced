/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hop.projects.project;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.hop.core.Const;
import org.apache.hop.core.config.DescribedVariablesConfigFile;
import org.apache.hop.core.config.IConfigFile;
import org.apache.hop.core.config.plugin.ConfigFile;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopFileException;
import org.apache.hop.core.json.HopJson;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.core.variables.DescribedVariable;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.core.vfs.HopVfs;
import org.apache.hop.metadata.api.HopMetadata;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.HopMetadataPropertyType;
import org.apache.hop.metadata.api.IHopMetadata;
import org.apache.hop.metadata.api.IHopMetadataSerializer;
import org.apache.hop.metadata.serializer.multi.MultiMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.projects.config.ProjectsConfig;
import org.apache.hop.projects.config.ProjectsConfigSingleton;
import org.apache.hop.projects.util.Defaults;
import org.apache.hop.projects.util.ProjectsUtil;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;

public class Project extends ConfigFile implements IConfigFile {

  @JsonIgnore private String configFilename;

  private String description;

  private String company;

  private String department;

  private String metadataBaseFolder;

  private String unitTestsBasePath;

  private String dataSetsCsvFolder;

  private boolean enforcingExecutionInHome;

  private String linkedProjectName;

  private MultiMetadataProvider metadataProvider;

  private List<Path> pipelinePaths;

  private List<Path> workflowPaths;

  private Map<PipelineMeta, List<TransformMeta>> pipelineTransformsMap;

  private Map<WorkflowMeta, List<ActionMeta>> workflowActionsMap;

  public Project() {
    super();
    metadataBaseFolder = "${" + ProjectsUtil.VARIABLE_PROJECT_HOME + "}/metadata";
    dataSetsCsvFolder = "${" + ProjectsUtil.VARIABLE_PROJECT_HOME + "}/datasets";
    unitTestsBasePath = "${" + ProjectsUtil.VARIABLE_PROJECT_HOME + "}";
    enforcingExecutionInHome = true;
  }

  public Project(String configFilename) {
    this();
    this.configFilename = configFilename;
  }

  @Override
  public void saveToFile() throws HopException {
    try {

      FileObject file = HopVfs.getFileObject(configFilename);

      // Does the parent folder of the file exist?
      //
      if (!file.getParent().exists()) {
        // Create it (and parents) to make sure.
        //
        file.getParent().createFolder();
      }

      ObjectMapper objectMapper = HopJson.newMapper();
      objectMapper.setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);
      objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

      OutputStream outputStream = HopVfs.getOutputStream(file, false);
      objectMapper.writeValue(outputStream, this);
    } catch (Exception e) {
      throw new HopException(
          "Error saving project configuration to file '" + configFilename + "'", e);
    }
  }

  @Override
  public void readFromFile() throws HopException {
    ObjectMapper objectMapper = HopJson.newMapper();
    try (InputStream inputStream = HopVfs.getInputStream(configFilename)) {
      Project project = objectMapper.readValue(inputStream, Project.class);

      this.description = project.description;
      this.company = project.company;
      this.department = project.department;
      this.metadataBaseFolder = project.metadataBaseFolder;
      this.unitTestsBasePath = project.unitTestsBasePath;
      this.dataSetsCsvFolder = project.dataSetsCsvFolder;
      this.enforcingExecutionInHome = project.enforcingExecutionInHome;
      this.configMap = project.configMap;
      this.linkedProjectName = project.linkedProjectName;
    } catch (Exception e) {
      throw new HopException(
          "Error saving project configuration to file '" + configFilename + "'", e);
    }
  }

  public void modifyVariables(
      IVariables variables,
      ProjectConfig projectConfig,
      List<String> configurationFiles,
      String environmentName)
      throws HopException {

    if (variables == null) {
      variables = Variables.getADefaultVariableSpace();
    }

    // See if we don't have an infinite loop in the project-parent-parent-... hierarchy...
    //
    verifyProjectsChain(projectConfig.getProjectName(), variables);

    // If there is a linked project we want to pick up the variables defined in the project
    // definition as well
    //
    Project linkedProject = null;
    String realLinkedProjectName = variables.resolve(linkedProjectName);
    if (StringUtils.isNotEmpty(realLinkedProjectName)) {

      ProjectConfig linkedProjectConfig =
          ProjectsConfigSingleton.getConfig().findProjectConfig(realLinkedProjectName);
      if (linkedProjectConfig != null) {
        try {
          linkedProject = linkedProjectConfig.loadProject(variables);
          // Apply the variables set in the linked project
          //
          linkedProject.modifyVariables(variables, linkedProjectConfig, new ArrayList<>(), null);
        } catch (HopException he) {
          LogChannel.GENERAL.logError(
              "Error loading configuration file of linked project '" + realLinkedProjectName + "'",
              he);
        }
      }
    }

    // Set the name of the active environment
    //
    variables.setVariable(
        Defaults.VARIABLE_HOP_PROJECT_NAME, Const.NVL(projectConfig.getProjectName(), ""));
    variables.setVariable(Defaults.VARIABLE_HOP_ENVIRONMENT_NAME, Const.NVL(environmentName, ""));

    // To allow circular logic where an environment file is relative to the project home
    //
    if (StringUtils.isNotEmpty(projectConfig.getProjectHome())) {
      String realValue = variables.resolve(projectConfig.getProjectHome());
      variables.setVariable(ProjectsUtil.VARIABLE_PROJECT_HOME, realValue);
    }

    // Apply the described variables from the various configuration files in the given order...
    //
    for (String configurationFile : configurationFiles) {
      String realConfigurationFile = variables.resolve(configurationFile);

      FileObject file = HopVfs.getFileObject(realConfigurationFile);
      try {
        if (file.exists()) {
          ConfigFile configFile = new DescribedVariablesConfigFile(realConfigurationFile);

          configFile.readFromFile();

          // Apply the variable values...
          //
          for (DescribedVariable describedVariable : configFile.getDescribedVariables()) {
            variables.setVariable(describedVariable.getName(), describedVariable.getValue());
          }

        } else {
          LogChannel.GENERAL.logError(
              "Configuration file '"
                  + realConfigurationFile
                  + "' does not exist to read variables from.");
        }
      } catch (Exception e) {
        LogChannel.GENERAL.logError(
            "Error reading described variables from configuration file '"
                + realConfigurationFile
                + "'",
            e);
      }
    }

    if (StringUtils.isNotEmpty(metadataBaseFolder)) {
      String realMetadataBaseFolder = variables.resolve(metadataBaseFolder);

      // If we have more than one metadata base folder to read metadata from, we can specify it
      // using comma separated values...
      //
      if (linkedProject != null) {
        // HOP_METADATA_FOLDER was set above in the variables.
        // We're going to simply append to it.
        //
        String parentMetadataFolder = variables.getVariable(Const.HOP_METADATA_FOLDER);
        if (StringUtils.isNotEmpty(parentMetadataFolder)) {
          realMetadataBaseFolder = parentMetadataFolder + "," + realMetadataBaseFolder;
        }
      }
      variables.setVariable(Const.HOP_METADATA_FOLDER, realMetadataBaseFolder);
    }
    if (StringUtils.isNotEmpty(unitTestsBasePath)) {
      String realValue = variables.resolve(unitTestsBasePath);
      variables.setVariable(ProjectsUtil.VARIABLE_HOP_UNIT_TESTS_FOLDER, realValue);
    }
    if (StringUtils.isNotEmpty(dataSetsCsvFolder)) {
      String realValue = variables.resolve(dataSetsCsvFolder);
      variables.setVariable(ProjectsUtil.VARIABLE_HOP_DATASETS_FOLDER, realValue);
    }
    for (DescribedVariable variable : getDescribedVariables()) {
      if (variable.getName() != null) {
        variables.setVariable(variable.getName(), variable.getValue());
      }
    }
  }

  /**
   * Let's check to see if there isn't an infinite loop in the project definition
   *
   * @throws HopException
   */
  public void verifyProjectsChain(String projectName, IVariables variables) throws HopException {

    // No linked project: no danger
    //
    if (StringUtils.isEmpty(linkedProjectName)) {
      return;
    }

    if (linkedProjectName.equals(projectName)) {
      throw new HopException(
          "Parent project '" + linkedProjectName + "' can not be the same as the project itself");
    }

    ProjectsConfig config = ProjectsConfigSingleton.getConfig();

    String realLinkedProjectName = variables.resolve(linkedProjectName);
    List<String> projectsList = new ArrayList<>();
    while (StringUtils.isNotEmpty(realLinkedProjectName)) {
      projectsList.add(realLinkedProjectName);
      ProjectConfig projectConfig = config.findProjectConfig(realLinkedProjectName);
      if (projectConfig != null) {
        Project linkedProject = projectConfig.loadProject(variables);
        if (linkedProject == null) {
          // Can't be loaded, break out of the loop
          realLinkedProjectName = null;
        } else {
          // See if this project has a parent...
          //
          if (StringUtils.isEmpty(linkedProject.linkedProjectName)) {
            // We're done
            realLinkedProjectName = null;
          } else {
            realLinkedProjectName = variables.resolve(linkedProject.linkedProjectName);
            if (StringUtils.isNotEmpty(realLinkedProjectName)) {
              // See if we've had this one before...
              //
              if (projectsList.contains(realLinkedProjectName)) {
                throw new HopException(
                    "There is a loop in the linked projects hierarchy: project "
                        + realLinkedProjectName
                        + " references itself");
              }
            }
          }
        }
      } else {
        // Project not found: config error, stop looking
        realLinkedProjectName = null;
      }
    }
  }

  /**
   * List all of the metadata types used in the current project
   *
   * @return the list of metadata type names used in this project
   * @throws HopException if the metadata classes can't be retrieved
   */
  public List<String> getMetadataTypes() throws HopException {
    List<String> metadataTypeNames = new ArrayList<>();
    if (metadataProvider != null) {
      List<Class<IHopMetadata>> metadataClasses = metadataProvider.getMetadataClasses();
      for (Class<IHopMetadata> metadataClass : metadataClasses) {
        IHopMetadataSerializer<IHopMetadata> metadataSerializer =
            metadataProvider.getSerializer(metadataClass);
        List<String> names = metadataSerializer.listObjectNames();
        Collections.sort(names);

        if (!names.isEmpty()) {
          metadataTypeNames.add(metadataClass.getName());
        }
      }
    }
    return metadataTypeNames;
  }

  /**
   * Build a map of all of the pipelines in the project.
   *
   * @throws IOException
   */
  private void buildPipelineMap(IVariables variables) throws IOException, HopFileException {
    pipelineTransformsMap = new HashMap<>();
    pipelinePaths = new ArrayList<>();
    File projectFolder =
        new File(String.valueOf(HopVfs.getFileObject(configFilename).getParent().getPath()));
    if (projectFolder.isDirectory()) {
      try (Stream<Path> walk = Files.walk(projectFolder.toPath())) {
        pipelinePaths =
            walk.filter(p -> !Files.isDirectory(p))
                .filter(f -> f.getFileName().toString().toLowerCase().endsWith("hpl"))
                .collect(Collectors.toList());
      }
    }
  }

  /**
   * Build a map of all of the workflows in the project.
   *
   * @throws IOException
   */
  private void buildWorkflowMap() throws IOException, HopFileException {
    workflowActionsMap = new HashMap<>();
    workflowPaths = new ArrayList<>();
    File projectFolder =
        new File(String.valueOf(HopVfs.getFileObject(configFilename).getParent().getPath()));
    if (projectFolder.isDirectory()) {
      try (Stream<Path> walk = Files.walk(projectFolder.toPath())) {
        workflowPaths =
            walk.filter(p -> !Files.isDirectory(p))
                .filter(f -> f.getFileName().toString().toLowerCase().endsWith("hwf"))
                .collect(Collectors.toList());
      }
    }
  }

  /**
   * Return a list of ll of the transform types that are used in the current project
   *
   * @param variables
   * @return
   * @throws IOException
   * @throws HopFileException
   */
  public List<String> getTransformTypes(IVariables variables) throws IOException, HopFileException {
    // build a map of all pipelines and transforms in the project.
    buildPipelineMap(variables);
    for (Path pipelinePath : pipelinePaths) {
      try {
        PipelineMeta pipelineMeta =
            new PipelineMeta(pipelinePath.toAbsolutePath().toString(), metadataProvider, variables);
        List<TransformMeta> transformMetas = pipelineMeta.getTransforms();
        pipelineTransformsMap.put(pipelineMeta, transformMetas);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    List<String> transformTypes = new ArrayList<>();
    for (PipelineMeta pipelineMeta : pipelineTransformsMap.keySet()) {
      for (TransformMeta transformMeta : pipelineMeta.getTransforms()) {
        if (!transformTypes.contains(transformMeta.getTypeId())) {
          transformTypes.add(transformMeta.getPluginId());
        }
      }
    }
    Collections.sort(transformTypes);
    return transformTypes;
  }

  /**
   * Return a list of ll of the action types that are used in the current project
   *
   * @param variables
   * @return
   * @throws IOException
   * @throws HopFileException
   */
  public List<String> getActionTypes(IVariables variables) throws HopException, IOException {
    buildWorkflowMap();
    for (Path workflowPath : workflowPaths) {
      try {
        WorkflowMeta workflowMeta =
            new WorkflowMeta(variables, workflowPath.toAbsolutePath().toString(), metadataProvider);
        List<ActionMeta> actionMetas = workflowMeta.getActions();
        workflowActionsMap.put(workflowMeta, actionMetas);
      } catch (Exception e) {
        System.err.println("error getting workflow actions");
        e.printStackTrace();
      }
    }

    List<String> actionTypes = new ArrayList<>();
    for (WorkflowMeta workflowMeta : workflowActionsMap.keySet()) {
      for (ActionMeta actionMeta : workflowMeta.getActions()) {
        if (!actionTypes.contains(actionMeta.getAction().getPluginId())) {
          actionTypes.add(actionMeta.getAction().getPluginId());
        }
      }
    }
    Collections.sort(actionTypes);
    return actionTypes;
  }

  public List<String> getPipelinesForMetadataItem(IVariables variables, String metadataItemName)
      throws HopException,
          NoSuchMethodException,
          InvocationTargetException,
          IllegalAccessException,
          IOException {

    List<String> resultStrings = new ArrayList<>();
    Map<HopMetadataPropertyType, String> metadataItems = new HashMap();

    // get the metadata item(s) with the provided metadataItemName
    List<Class<IHopMetadata>> metadataClasses = metadataProvider.getMetadataClasses();

    // walk over all metadata classes, build a list of available metadata types for the provided
    // metadata item.
    for (Class<IHopMetadata> metadataClass : metadataClasses) {
      IHopMetadataSerializer<IHopMetadata> metadataSerializer =
          metadataProvider.getSerializer(metadataClass);

      List<String> names = metadataSerializer.listObjectNames();

      // add the available HopMetadataPropertyTypes from @HopMetadata and add to metadataItems
      if (names.contains(metadataItemName)) {
        if (metadataClass.isAnnotationPresent(HopMetadata.class)) {
          HopMetadata annotation = metadataClass.getAnnotation(HopMetadata.class);
          HopMetadataPropertyType hopMetadataPropertyType = annotation.hopMetadataPropertyType();
          metadataItems.put(hopMetadataPropertyType, metadataItemName);
        }
      }
    }

    // build the map of transforms per pipeline if we don't have it available
    if (pipelineTransformsMap == null || pipelineTransformsMap.size() == 0) {
      getTransformTypes(variables);
    }

    // walk over all transforms in all pipelines, find occurrences of this metadata item.
    for (PipelineMeta pipelineMeta : pipelineTransformsMap.keySet()) {
      for (TransformMeta transformMeta : pipelineTransformsMap.get(pipelineMeta)) {

        // walk over the fields in a transform and check their @HopMetadataProperty annotations for
        // HopMetadataPropertyType
        Field[] fields = transformMeta.getTransform().getClass().getDeclaredFields();
        for (Field field : fields) {
          if (field.isAnnotationPresent(HopMetadataProperty.class)) {
            HopMetadataProperty annotation = field.getAnnotation(HopMetadataProperty.class);
            HopMetadataPropertyType hopMetadataPropertyType = annotation.hopMetadataPropertyType();
            if (metadataItems.keySet().contains(hopMetadataPropertyType)) {
              Method method =
                  transformMeta
                      .getTransform()
                      .getClass()
                      .getMethod("get" + StringUtils.capitalize(field.getName()));
              String resultStr = (String) method.invoke(transformMeta.getTransform());
              resultStrings.add(
                  pipelineMeta.getFilename()
                      + " -> "
                      + transformMeta.getName()
                      + " -> "
                      + resultStr
                      + " ("
                      + hopMetadataPropertyType
                      + ")");
            }
          }
        }
      }
    }
    return resultStrings;
  }

  public List<String> getWorkflowsForMetadataItem(IVariables variables, String metadataItemName)
      throws HopException,
          NoSuchMethodException,
          InvocationTargetException,
          IllegalAccessException,
          IOException {

    List<String> resultStrings = new ArrayList<>();
    Map<HopMetadataPropertyType, String> metadataItems = new HashMap();

    // get the metadata item(s) with the provided metadataItemName
    List<Class<IHopMetadata>> metadataClasses = metadataProvider.getMetadataClasses();

    // walk over all metadata classes, build a list of available metadata types for the provided
    // metadata item.
    for (Class<IHopMetadata> metadataClass : metadataClasses) {
      IHopMetadataSerializer<IHopMetadata> metadataSerializer =
          metadataProvider.getSerializer(metadataClass);

      List<String> names = metadataSerializer.listObjectNames();

      // add the available HopMetadataPropertyTypes from @HopMetadata and add to metadataItems
      if (names.contains(metadataItemName)) {
        if (metadataClass.isAnnotationPresent(HopMetadata.class)) {
          HopMetadata annotation = metadataClass.getAnnotation(HopMetadata.class);
          HopMetadataPropertyType hopMetadataPropertyType = annotation.hopMetadataPropertyType();
          metadataItems.put(hopMetadataPropertyType, metadataItemName);
        }
      }
    }

    // build the map of actions per workflow if we don't have it available
    if (workflowActionsMap == null || workflowActionsMap.size() == 0) {
      getActionTypes(variables);
    }

    // walk over all transforms in all pipelines, find occurrences of this metadata item.
    for (WorkflowMeta workflowMeta : workflowActionsMap.keySet()) {
      for (ActionMeta actionMeta : workflowActionsMap.get(workflowMeta)) {

        // walk over the fields in a transform and check their @HopMetadataProperty annotations for
        // HopMetadataPropertyType
        Field[] fields = actionMeta.getAction().getClass().getDeclaredFields();
        for (Field field : fields) {
          if (field.isAnnotationPresent(HopMetadataProperty.class)) {
            HopMetadataProperty annotation = field.getAnnotation(HopMetadataProperty.class);
            HopMetadataPropertyType hopMetadataPropertyType = annotation.hopMetadataPropertyType();
            if (metadataItems.keySet().contains(hopMetadataPropertyType)) {
              Method method =
                  actionMeta
                      .getAction()
                      .getClass()
                      .getMethod("get" + StringUtils.capitalize(field.getName()));
              String resultStr = (String) method.invoke(actionMeta.getAction());
              resultStrings.add(
                  workflowMeta.getFilename()
                      + " -> "
                      + actionMeta.getName()
                      + " -> "
                      + resultStr
                      + " ("
                      + hopMetadataPropertyType
                      + ")");
            }
          }
        }
      }
    }
    return resultStrings;
  }

  /**
   * Gets configFilename
   *
   * @return value of configFilename
   */
  @Override
  public String getConfigFilename() {
    return configFilename;
  }

  /**
   * @param configFilename The configFilename to set
   */
  @Override
  public void setConfigFilename(String configFilename) {
    this.configFilename = configFilename;
  }

  /**
   * Gets description
   *
   * @return value of description
   */
  public String getDescription() {
    return description;
  }

  /**
   * @param description The description to set
   */
  public void setDescription(String description) {
    this.description = description;
  }

  /**
   * Gets company
   *
   * @return value of company
   */
  public String getCompany() {
    return company;
  }

  /**
   * @param company The company to set
   */
  public void setCompany(String company) {
    this.company = company;
  }

  /**
   * Gets department
   *
   * @return value of department
   */
  public String getDepartment() {
    return department;
  }

  /**
   * @param department The department to set
   */
  public void setDepartment(String department) {
    this.department = department;
  }

  /**
   * Gets metadataBaseFolder
   *
   * @return value of metadataBaseFolder
   */
  public String getMetadataBaseFolder() {
    return metadataBaseFolder;
  }

  /**
   * @param metadataBaseFolder The metadataBaseFolder to set
   */
  public void setMetadataBaseFolder(String metadataBaseFolder) {
    this.metadataBaseFolder = metadataBaseFolder;
  }

  /**
   * Gets unitTestsBasePath
   *
   * @return value of unitTestsBasePath
   */
  public String getUnitTestsBasePath() {
    return unitTestsBasePath;
  }

  /**
   * @param unitTestsBasePath The unitTestsBasePath to set
   */
  public void setUnitTestsBasePath(String unitTestsBasePath) {
    this.unitTestsBasePath = unitTestsBasePath;
  }

  /**
   * Gets dataSetsCsvFolder
   *
   * @return value of dataSetsCsvFolder
   */
  public String getDataSetsCsvFolder() {
    return dataSetsCsvFolder;
  }

  /**
   * @param dataSetsCsvFolder The dataSetsCsvFolder to set
   */
  public void setDataSetsCsvFolder(String dataSetsCsvFolder) {
    this.dataSetsCsvFolder = dataSetsCsvFolder;
  }

  /**
   * Gets enforcingExecutionInHome
   *
   * @return value of enforcingExecutionInHome
   */
  public boolean isEnforcingExecutionInHome() {
    return enforcingExecutionInHome;
  }

  /**
   * @param enforcingExecutionInHome The enforcingExecutionInHome to set
   */
  public void setEnforcingExecutionInHome(boolean enforcingExecutionInHome) {
    this.enforcingExecutionInHome = enforcingExecutionInHome;
  }

  /**
   * Gets parentProjectName
   *
   * @return value of parentProjectName
   */
  public String getLinkedProjectName() {
    return linkedProjectName;
  }

  /**
   * @param linkedProjectName The parentProjectName to set
   */
  public void setLinkedProjectName(String linkedProjectName) {
    this.linkedProjectName = linkedProjectName;
  }

  public MultiMetadataProvider getMetadataProvider() {
    return metadataProvider;
  }

  public void setMetadataProvider(MultiMetadataProvider metadataProvider) {
    this.metadataProvider = metadataProvider;
  }
}
