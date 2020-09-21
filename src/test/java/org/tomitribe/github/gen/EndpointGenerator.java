/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.tomitribe.github.gen;

import lombok.Data;
import org.tomitribe.github.gen.code.endpoint.Endpoint;
import org.tomitribe.github.gen.code.endpoint.EndpointMethod;
import org.tomitribe.github.gen.code.model.Clazz;
import org.tomitribe.github.gen.code.model.ClazzReference;
import org.tomitribe.github.gen.code.model.Field;
import org.tomitribe.github.gen.openapi.Content;
import org.tomitribe.github.gen.openapi.Github;
import org.tomitribe.github.gen.openapi.Method;
import org.tomitribe.github.gen.openapi.OpenApi;
import org.tomitribe.github.gen.openapi.Parameter;
import org.tomitribe.github.gen.openapi.Preview;
import org.tomitribe.github.gen.openapi.Response;
import org.tomitribe.github.gen.openapi.Schema;
import org.tomitribe.github.gen.util.Words;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.Boolean.TRUE;

@Data
public class EndpointGenerator {


    private final ModelGenerator modelGenerator;
    private final String modelPackage;
    private ComponentIndex componentIndex;
    private final String endpointPackage;

    public EndpointGenerator() {
        this.modelPackage = "org.tomitribe.github.model";
        this.endpointPackage = "org.tomitribe.github.client";
        this.modelGenerator = new ModelGenerator(modelPackage);
    }

    public List<Endpoint> build(final OpenApi openApi) {
        this.componentIndex = new ComponentIndex(modelGenerator, openApi);

        final Map<String, List<EndpointMethod>> categories = openApi.getMethods()
                .filter(this::isSupported)
                .map(this::createMethod)
                .collect(Collectors.groupingBy(EndpointMethod::getCategory));

        final List<Endpoint> endpoints = new ArrayList<>();

        for (final Map.Entry<String, List<EndpointMethod>> entry : categories.entrySet()) {
            final String category = entry.getKey();
            final List<EndpointMethod> methods = entry.getValue();

            final String typeName = Words.getTypeName(category + "-client");

            endpoints.add(Endpoint.builder()
                    .className(typeName)
                    .methods(methods)
                    .build());
        }

        final List<Clazz> classes = modelGenerator.getClasses();
        ResolveReferences.resolve(classes, componentIndex, endpoints);

        // We no longer need any of the ClazzReference instances
        // Remove them so they aren't rendered
        classes.removeIf(clazz -> clazz instanceof ClazzReference);

        return endpoints;
    }

    private boolean isSupported(final Method method) {
        /*
         * We use the summary to create the method name
         */
        if (method.getSummary() == null) return false;

        /*
         * If we don't have response information, we can't do anything
         */
        if (method.getResponses() == null) return false;

        /*
         * If there are no 200 range responses, we don't currently support it
         * There are some that legitimately return 302 redirects, which we
         * should add support for in future versions.
         */
        if (method.getResponses().keySet().stream().noneMatch(s -> s.startsWith("2"))) return false;

        /*
         * If it returns void or returns json, we're good.
         * If it returns neither of those things we currently can't support it.
         *
         * There are API calls that return binary data like zips, which we should
         * add support for in future versions.
         */
        if (!returnsVoid(method) && !returnsApplicationJson(method)) return false;

        return true;
    }

    private boolean returnsApplicationJson(final Method method) {
        return method.getResponses().values().stream()
                .map(Response::getContent)
                .map(Map::keySet)
                .flatMap(Collection::stream)
                .anyMatch(s -> s.equals("application/json"));
    }

    private boolean returnsVoid(final Method method) {
        return method.getResponses().keySet().stream().anyMatch(s -> s.equals("204"));
    }

    private EndpointMethod createMethod(final Method method) {
        final String methodName = asMethodName(method.getSummary());
        final String requestClassName = asRequestName(method.getSummary());

        final Clazz requestClass = generateRequestClass(requestClassName, method.getParameters());
        final Clazz responseClass = generateResponseClass(method);

        final EndpointMethod.Builder builder = EndpointMethod.builder()
                .method(method.getName())
                .path(method.getPath().getName())
                .javaMethod(methodName)
                .request(requestClass)
                .response(responseClass)
                .summary(method.getSummary())
                .operationId(method.getOperationId());

        if (method.getExternalDocs() != null) {
            builder.docs(method.getExternalDocs().getUrl());
        }

        final Github github = method.getGithub();
        if (github != null) {
            builder.category(github.getCategory())
                    .subcategory(github.getSubcategory())
                    .removalDate(github.getRemovalDate())
                    .deprecationDate(github.getDeprecationDate())
                    .enabledForGitHubApps(TRUE.equals(github.getEnabledForGitHubApps()))
                    .githubCloudOnly(TRUE.equals(github.getGithubCloudOnly()));

            if (github.getPreviews() != null) {
                for (final Preview preview : github.getPreviews()) {
                    builder.preview(preview.getName());
                }
            }
        }

        return builder.build();
    }

    private Clazz generateResponseClass(final Method method) {
        final Content jsonResponse = get2xxJsonResponse(method);

        return modelGenerator.build(jsonResponse.getSchema());
    }

    private Content get2xxJsonResponse(final Method method) {
        if (method.getResponses() == null) {
            throw new IllegalStateException("Method has no responses: " + method);
        }
        if (method.getResponses().values() == null) {
            throw new IllegalStateException("Method has no responses: " + method);
        }
        final Response ok = method.getResponses().values().stream()
                .filter(response -> response.getName().startsWith("2"))
                .min(Comparator.comparing(Response::getName))
                .orElseThrow(() -> new IllegalStateException("No 200 range responses found"));

        final Content jsonResponse = ok.getContent().get("application/json");
        if (jsonResponse == null) throw new IllegalStateException("Expected 'application/json' content");
        return jsonResponse;
    }

    private Clazz generateRequestClass(final String requestClassName, final List<Parameter> parameters) {
        final Clazz.Builder clazz = Clazz.builder().name(modelPackage + "." + requestClassName);
        for (final Parameter parameter : parameters) {
            final Schema schema = getSchema(parameter);

            final String name = Optional.ofNullable(parameter.getName())
                    .orElse(schema.getName());
            final Field field = modelGenerator.getField(clazz, name, schema);

            if (parameter.getIn() != null) {
                final Field.In in = Field.In.valueOf(parameter.getIn().toUpperCase());
                field.setIn(in);
            }
            clazz.field(field);
        }
        return clazz.build();
    }

    private Schema getSchema(final Parameter parameter) {
        if (parameter.getSchema() != null) {
            return parameter.getSchema();
        }

        if (parameter.getRef() != null) {
            final Schema schema = new Schema();
            schema.setRef(parameter.getRef());
            return schema;
        }

        throw new IllegalStateException("No schema found for parameter: " + parameter);
    }

    private String asRequestName(final String summary) {
        return Words.getTypeName(summary);
    }

    private String asMethodName(final String summary) {
        return Words.getVariableName(summary);
    }
//    public EndpointClazz
}
