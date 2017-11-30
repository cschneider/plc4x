/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package org.apache.plc4x.camel.util;

import java.util.Optional;
import java.util.stream.Stream;

public class StreamUtils {

    /**
     * Returns a {@link Stream} of the supplied {@link Optional}. Obsolete with Java &gt; 9.
     *
     * @param optional the {@link Optional} which should be converted to a {@link Stream}.
     * @param <T>      the type contained by the {@link Optional}.
     * @return the {@link Stream} of zero or one elements.
     */
    public static <T> Stream<T> streamOf(@SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<T> optional) {
        return Stream.of(optional)
            .filter(Optional::isPresent)
            .map(Optional::get);
    }
}
