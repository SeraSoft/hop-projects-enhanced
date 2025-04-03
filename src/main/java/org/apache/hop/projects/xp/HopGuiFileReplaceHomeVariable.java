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

package org.apache.hop.projects.xp;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.extension.ExtensionPoint;
import org.apache.hop.core.extension.IExtensionPoint;
import org.apache.hop.core.logging.ILogChannel;
import org.apache.hop.core.util.StringUtil;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.vfs.HopVfs;
import org.apache.hop.projects.config.ProjectsConfig;
import org.apache.hop.projects.config.ProjectsConfigSingleton;
import org.apache.hop.projects.project.ProjectConfig;
import org.apache.hop.projects.util.ProjectsUtil;
import org.apache.hop.ui.core.gui.HopNamespace;
import org.apache.hop.ui.hopgui.delegates.HopGuiFileOpenedExtension;

@ExtensionPoint(
    id = "HopGuiFileReplaceHomeVariable",
    extensionPointId = "HopGuiFileOpenedDialog",
    description =
        "Replace ${PROJECT_HOME} or ${LINKED_PROJECT_HOME} in selected filenames as a best practice aid")
public class HopGuiFileReplaceHomeVariable implements IExtensionPoint<HopGuiFileOpenedExtension> {

  // TODO make this optional

  @Override
  public void callExtensionPoint(
      ILogChannel log, IVariables variables, HopGuiFileOpenedExtension ext) {

    // Is there an active project?
    //
    String projectName = HopNamespace.getNamespace();
    if (StringUtil.isEmpty(projectName)) {
      return;
    }
    ProjectsConfig config = ProjectsConfigSingleton.getConfig();
    ProjectConfig projectConfig = config.findProjectConfig(projectName);
    if (projectConfig == null) {
      return;
    }

    String linkedProjectName;
    try {
      linkedProjectName = projectConfig.loadProject(variables).getLinkedProjectName();
    } catch (HopException e) {
      throw new RuntimeException(e);
    }

    ProjectConfig linkedProjectConfig = null;
    if (linkedProjectName != null) {
      log.logDebug("Linked project found for [{0}] named [{1}]", projectName, linkedProjectName);
      linkedProjectConfig = config.findProjectConfig(linkedProjectName);
    } else {
      log.logDebug("No linkedProject found for [{0}]", projectName);
    }

    String linkedProjectHomeFolder = null;
    String homeFolder = null;
    if (variables != null) {
      homeFolder = variables.resolve(projectConfig.getProjectHome());
      if (linkedProjectConfig != null) {
        linkedProjectHomeFolder = variables.resolve(linkedProjectConfig.getProjectHome());
      }
    } else {
      homeFolder = projectConfig.getProjectHome();
      if (linkedProjectConfig != null) {
        linkedProjectHomeFolder = linkedProjectConfig.getProjectHome();
      }
    }

    try {
      String absoluteHomeFolder = null;
      String absoluteLPHomeFolder = null;

      FileObject extFile = HopVfs.getFileObject(ext.filename);
      String absoluteExtFile = extFile.getName().getPath();

      if (StringUtils.isNotEmpty(homeFolder)) {
        FileObject home = HopVfs.getFileObject(homeFolder);
        absoluteHomeFolder = home.getName().getPath();
        // Make the URI always end with a /
        if (!absoluteHomeFolder.endsWith("/")) {
          absoluteHomeFolder += "/";
        }
      }

      if (linkedProjectHomeFolder != null) {
        FileObject linkedProjectHome = HopVfs.getFileObject(linkedProjectHomeFolder);
        absoluteLPHomeFolder = linkedProjectHome.getName().getPath();
        // Make the URI always end with a /
        if (!absoluteLPHomeFolder.endsWith("/")) {
          absoluteLPHomeFolder += "/";
        }
      }

      // Replace the linked project's home variable in the filename
      //
      if (absoluteExtFile.startsWith(absoluteHomeFolder)) {
        ext.filename =
            "${"
                + ProjectsUtil.VARIABLE_PROJECT_HOME
                + "}/"
                + absoluteExtFile.substring(absoluteHomeFolder.length());
      } else if (absoluteExtFile.startsWith(absoluteLPHomeFolder)) {
        ext.filename =
            "${"
                + ProjectsUtil.VARIABLE_LINKED_PROJECT_HOME
                + "}/"
                + absoluteExtFile.substring(absoluteLPHomeFolder.length());
      }

    } catch (Exception e) {
      log.logError("Error setting default folder for project " + projectName, e);
    }
  }
}
