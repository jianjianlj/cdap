/*
 * Copyright 2012-2014 Continuuity, Inc.
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

package com.continuuity.common.collect;

import com.google.common.base.Preconditions;
import com.google.common.collect.ObjectArrays;

import java.util.Arrays;

/**
 * This collector will keep only the most recent N elements. It will
 * never return false, but keeps a bound on the memory it uses.
 *
 * @param <Element> Type of element.
 */
public class LastNCollector<Element> implements Collector<Element> {
  private final Class<Element> clazz;
  private final Element[] elements;
  private int count = 0;

  public LastNCollector(int n, Class<Element> clazz) {
    Preconditions.checkArgument(n > 0, "n must be greater than 0");
    this.clazz = clazz;
    elements = ObjectArrays.newArray(clazz, n);
  }

  @Override
  public boolean addElement(Element element) {
    elements[count % elements.length] = element;
    count++;
    return true;
  }

  @Override
  public Element[] finish() {
    if (count < elements.length) {
      return Arrays.copyOf(elements, count);
    } else {
      int mod = count % elements.length;
      Element[] array = ObjectArrays.newArray(clazz, elements.length);
      System.arraycopy(elements, mod, array, 0, elements.length - mod);
      System.arraycopy(elements, 0, array, elements.length - mod, mod);
      return array;
    }
  }
}

