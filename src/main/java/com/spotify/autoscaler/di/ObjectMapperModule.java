/*-
 * -\-\-
 * bigtable-autoscaler
 * --
 * Copyright (C) 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.autoscaler.di;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import dagger.Module;
import dagger.Provides;
import io.norberg.automatter.jackson.AutoMatterModule;
import javax.inject.Singleton;

@Module
public class ObjectMapperModule {

  @Provides
  @Singleton
  public static ObjectMapper mapper() {
    return new ObjectMapper()
        .registerModule(new AutoMatterModule())
        .registerModule(new Jdk8Module());
  }
}
