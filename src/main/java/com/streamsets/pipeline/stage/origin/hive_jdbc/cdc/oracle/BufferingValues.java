/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.hive_jdbc.cdc.oracle;

import com.streamsets.pipeline.api.Label;

public enum  BufferingValues implements Label {

  IN_MEMORY("In Memory"),
  ON_DISK("On Disk");

  private final String label;

  BufferingValues(String label) {
    this.label = label;
  }

  @Override
  public String getLabel() {
    return label;
  }
}