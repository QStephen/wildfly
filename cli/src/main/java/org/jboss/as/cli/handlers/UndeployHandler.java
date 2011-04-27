/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.cli.handlers;


import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT_REMOVE_OPERATION;
import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT_UNDEPLOY_OPERATION;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandLineCompleter;
import org.jboss.as.cli.ParsedArguments;
import org.jboss.as.cli.Util;
import org.jboss.as.cli.impl.ArgumentWithValue;
import org.jboss.as.cli.impl.ArgumentWithoutValue;
import org.jboss.as.cli.operation.OperationFormatException;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestBuilder;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
public class UndeployHandler extends BatchModeCommandHandler {

    private final ArgumentWithoutValue l;
    private final ArgumentWithValue name;
    private final ArgumentWithValue serverGroups;
    private final ArgumentWithoutValue allServerGroups;

    public UndeployHandler() {
        super("undeploy", true);

        SimpleArgumentTabCompleter argsCompleter = (SimpleArgumentTabCompleter) this.getArgumentCompleter();

        l = new ArgumentWithoutValue("-l");
        l.setExclusive(true);
        argsCompleter.addArgument(l);

        name = new ArgumentWithValue(false, new CommandLineCompleter() {
            @Override
            public int complete(CommandContext ctx, String buffer, int cursor, List<String> candidates) {

                int nextCharIndex = 0;
                while (nextCharIndex < buffer.length()) {
                    if (!Character.isWhitespace(buffer.charAt(nextCharIndex))) {
                        break;
                    }
                    ++nextCharIndex;
                }

                if(ctx.getModelControllerClient() != null) {
                    List<String> deployments = Util.getDeployments(ctx.getModelControllerClient());
                    if(deployments.isEmpty()) {
                        return -1;
                    }

                    String opBuffer = buffer.substring(nextCharIndex).trim();
                    if (opBuffer.isEmpty()) {
                        candidates.addAll(deployments);
                    } else {
                        for(String name : deployments) {
                            if(name.startsWith(opBuffer)) {
                                candidates.add(name);
                            }
                        }
                        Collections.sort(candidates);
                    }
                    return nextCharIndex;
                } else {
                    return -1;
                }

            }}, 0, "--name");
        name.addCantAppearAfter(l);
        argsCompleter.addArgument(name);

        allServerGroups = new ArgumentWithoutValue("--all-server-groups") {
            @Override
            public boolean isAvailable(CommandContext ctx) {
                return ctx.isDomainMode();
            }
        };
        argsCompleter.addArgument(allServerGroups);
        allServerGroups.addRequiredPreceding(name);

        serverGroups = new ArgumentWithValue(true, new CommandLineCompleter() {
            @Override
            public int complete(CommandContext ctx, String buffer, int cursor, List<String> candidates) {
                List<String> allGroups = Util.getServerGroups(ctx.getModelControllerClient());
                if(buffer.isEmpty()) {
                    candidates.addAll(allGroups);
                    Collections.sort(candidates);
                    return 0;
                }

                final String[] groups = buffer.split(",+");

                final String chunk;
                final int lastGroupIndex;
                if(buffer.charAt(buffer.length() - 1) == ',') {
                    lastGroupIndex = groups.length;
                    chunk = null;
                } else {
                    lastGroupIndex = groups.length - 1;
                    chunk = groups[groups.length - 1];
                }

                for(int i = 0; i < lastGroupIndex; ++i) {
                    allGroups.remove(groups[i]);
                }

                final int result;
                if(chunk == null) {
                    candidates.addAll(allGroups);
                    result = buffer.length();
                } else {
                    for(String group : allGroups) {
                        if(group.startsWith(chunk)) {
                            candidates.add(group);
                        }
                    }
                    result = buffer.lastIndexOf(',') + 1;
                }
                Collections.sort(candidates);
                return result;
            }}, "--server-groups") {
            @Override
            public boolean isAvailable(CommandContext ctx) {
                return ctx.isDomainMode();
            }
        };
        serverGroups.addRequiredPreceding(name);
        argsCompleter.addArgument(serverGroups);

        serverGroups.addCantAppearAfter(allServerGroups);
        allServerGroups.addCantAppearAfter(serverGroups);
    }

    @Override
    protected void doHandle(CommandContext ctx) {

        ModelControllerClient client = ctx.getModelControllerClient();
        ParsedArguments args = ctx.getParsedArguments();
        boolean l = this.l.isPresent(args);
        if(!args.hasArguments() || l) {
            printList(ctx, Util.getDeployments(client), l);
            return;
        }

        final String name = this.name.getValue(ctx.getParsedArguments());
        if (name == null) {
            printList(ctx, Util.getDeployments(client), l);
            return;
        }

        ModelNode request;
        try {
            request = buildRequest(ctx);
        } catch (OperationFormatException e) {
            ctx.printLine(e.getLocalizedMessage());
            return;
        }

        ModelNode result;
        try {
            result = client.execute(request);
        } catch (Exception e) {
            ctx.printLine("Undeploy failed: " + e.getLocalizedMessage());
            return;
        }
        if (!Util.isSuccess(result)) {
            ctx.printLine("Undeploy failed: " + Util.getFailureDescription(result));
            return;
        }

        ctx.printLine("Successfully undeployed " + name + ".");
    }

    @Override
    public ModelNode buildRequest(CommandContext ctx) throws OperationFormatException {

        ModelNode composite = new ModelNode();
        composite.get("operation").set("composite");
        composite.get("address").setEmptyList();
        ModelNode steps = composite.get("steps");

        final ParsedArguments args = ctx.getParsedArguments();
        final String name = this.name.getValue(args);
        if(name == null) {
            throw new OperationFormatException("Required argument name are missing.");
        }

        DefaultOperationRequestBuilder builder;

        if(ctx.isDomainMode()) {
            final List<String> serverGroups;
            if(allServerGroups.isPresent(args)) {
                serverGroups = Util.getServerGroups(ctx.getModelControllerClient());
            } else {
                String serverGroupsStr = this.serverGroups.getValue(args);
                if(serverGroupsStr == null) {
                    new OperationFormatException("Either --all-server-groups or --server-groups must be specified.");
                }
                serverGroups = Arrays.asList(serverGroupsStr.split(","));
            }

            if(serverGroups.isEmpty()) {
                new OperationFormatException("No server group is available.");
            }

            for (String group : serverGroups) {
                ModelNode groupStep = Util.configureDeploymentOperation(DEPLOYMENT_UNDEPLOY_OPERATION, name, group);
                steps.add(groupStep);
            }

            for (String group : serverGroups) {
                ModelNode groupStep = Util.configureDeploymentOperation(DEPLOYMENT_REMOVE_OPERATION, name, group);
                steps.add(groupStep);
            }
        } else {
            builder = new DefaultOperationRequestBuilder();
            builder.setOperationName("undeploy");
            builder.addNode("deployment", name);
            steps.add(builder.buildRequest());
        }

        builder = new DefaultOperationRequestBuilder();
        builder.setOperationName("remove");
        builder.addNode("deployment", name);
        steps.add(builder.buildRequest());
        return composite;
    }
}
