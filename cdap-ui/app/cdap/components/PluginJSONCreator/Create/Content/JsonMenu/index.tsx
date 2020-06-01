/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import If from 'components/If';
import ClosedJsonMenu from 'components/PluginJSONCreator/Create/Content/JsonMenu/ClosedJsonMenu';
import JsonLiveViewer from 'components/PluginJSONCreator/Create/Content/JsonMenu/JsonLiveViewer';
import {
  downloadPluginJSON,
  getJSONConfig,
  parsePluginJSON,
} from 'components/PluginJSONCreator/Create/Content/JsonMenu/utilities';
import { ICreateContext } from 'components/PluginJSONCreator/CreateContextConnect';
import * as React from 'react';

export enum JSONStatusMessage {
  Pending = '',
  Success = 'SUCCESS',
  Fail = 'FAIL',
}

const JsonMenu: React.FC<ICreateContext> = ({
  pluginName,
  pluginType,
  displayName,
  emitAlerts,
  emitErrors,
  configurationGroups,
  groupToInfo,
  groupToWidgets,
  widgetInfo,
  widgetToAttributes,
  jsonView,
  setJsonView,
  outputName,
  setPluginState,
}) => {
  const pluginData = {
    pluginName,
    pluginType,
    displayName,
    emitAlerts,
    emitErrors,
    configurationGroups,
    groupToInfo,
    groupToWidgets,
    widgetToInfo,
    widgetToAttributes,
    outputName,
  };

  const [JSONStatus, setJSONStatus] = React.useState(JSONStatusMessage.Pending);
  const [JSONErrorMessage, setJSONErrorMessage] = React.useState('');

  // When JSON error occurs, show the error message for 2 seconds.
  React.useEffect(() => {
    const timer = setTimeout(() => setJSONStatus(JSONStatusMessage.Pending), 2000);

    return () => {
      clearTimeout(timer);
    };
  }, [JSONStatus]);

  const jsonFilename = `${pluginName ? pluginName : '<PluginName>'}-${
    pluginType ? pluginType : '<PluginType>'
  }.json`;

  const downloadDisabled =
    !pluginName || pluginName.length === 0 || !pluginType || pluginType.length === 0;

  const onDownloadClick = () => {
    downloadPluginJSON(pluginData);
  };

  const populateImportResults = (filename: string, fileContent: string) => {
    try {
      const pluginJSON = JSON.parse(fileContent);

      const {
        basicPluginInfo,
        newConfigurationGroups,
        newGroupToInfo,
        newGroupToWidgets,
        newWidgetToInfo,
        newWidgetToAttributes,
        newOutputName,
      } = parsePluginJSON(filename, pluginJSON);

      setPluginState({
        basicPluginInfo,
        configurationGroups: newConfigurationGroups,
        groupToInfo: newGroupToInfo,
        groupToWidgets: newGroupToWidgets,
        widgetToInfo: newWidgetToInfo,
        widgetToAttributes: newWidgetToAttributes,
        outputName: newOutputName,
      });
      setJSONStatus(JSONStatusMessage.Success);
      setJSONErrorMessage(null);
    } catch (e) {
      setJSONStatus(JSONStatusMessage.Fail);
      setJSONErrorMessage(`${e.name}: ${e.message}`);
    }
  };

  const expandJSONView = () => {
    setJsonView(true);
  };

  const collapseJSONView = () => {
    setJsonView(false);
  };

  return (
    <div>
      <If condition={jsonView}>
        <JsonLiveViewer
<<<<<<< HEAD
          pluginName={pluginName}
          pluginType={pluginType}
          displayName={displayName}
          emitAlerts={emitAlerts}
          emitErrors={emitErrors}
          configurationGroups={configurationGroups}
          groupToInfo={groupToInfo}
          groupToWidgets={groupToWidgets}
          widgetInfo={widgetInfo}
          widgetToAttributes={widgetToAttributes}
          jsonView={jsonView}
          setJsonView={setJsonView}
          outputName={outputName}
=======
          JSONConfig={getJSONConfig(pluginData)}
          downloadDisabled={downloadDisabled}
          collapseJSONView={collapseJSONView}
          onDownloadClick={onDownloadClick}
          populateImportResults={populateImportResults}
          jsonFilename={jsonFilename}
          JSONStatus={JSONStatus}
          JSONErrorMessage={JSONErrorMessage}
>>>>>>> 797996d7ff1... [CDAP-16874] Importing existing plugin JSON file (plugin JSON Creator)
        />
      </If>
      <If condition={!jsonView}>
        <ClosedJsonMenu
<<<<<<< HEAD
          pluginName={pluginName}
          pluginType={pluginType}
          displayName={displayName}
          emitAlerts={emitAlerts}
          emitErrors={emitErrors}
          configurationGroups={configurationGroups}
          groupToInfo={groupToInfo}
          groupToWidgets={groupToWidgets}
          widgetInfo={widgetInfo}
          widgetToAttributes={widgetToAttributes}
          jsonView={jsonView}
          setJsonView={setJsonView}
          outputName={outputName}
=======
          downloadDisabled={downloadDisabled}
          onDownloadClick={onDownloadClick}
          expandJSONView={expandJSONView}
          populateImportResults={populateImportResults}
>>>>>>> 797996d7ff1... [CDAP-16874] Importing existing plugin JSON file (plugin JSON Creator)
        />
      </If>
    </div>
  );
};

export default JsonMenu;
