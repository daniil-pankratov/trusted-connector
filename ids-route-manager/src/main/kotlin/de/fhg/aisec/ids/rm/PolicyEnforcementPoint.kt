/*-
 * ========================LICENSE_START=================================
 * ids-route-manager
 * %%
 * Copyright (C) 2019 Fraunhofer AISEC
 * %%
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
 * =========================LICENSE_END==================================
 */
package de.fhg.aisec.ids.rm

import com.google.common.collect.MapMaker
import de.fhg.aisec.ids.api.policy.DecisionRequest
import de.fhg.aisec.ids.api.policy.PolicyDecision
import de.fhg.aisec.ids.api.policy.ServiceNode
import de.fhg.aisec.ids.api.policy.TransformationDecision
import de.fraunhofer.iais.eis.BinaryOperator
import de.fraunhofer.iais.eis.Constraint
import de.fraunhofer.iais.eis.LeftOperand
import org.apache.camel.*
import org.apache.camel.model.FromDefinition
import org.apache.camel.model.RouteDefinition
import org.apache.camel.model.ToDefinition
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.util.*
import java.util.concurrent.CompletableFuture

class PolicyEnforcementPoint internal constructor(
    private val destinationNode: NamedNode,
    private val target: Processor) : AsyncProcessor {
    /**
     * The method performs flow control and calls Exchange.setException() when necessary
     * It iterates through nodes in CamelRoute (<from>, <log>, <choice>, <process>, <to>, ...)
     * and launches node specific usage enforcement actions.
     *
     * At node <from>: protect exchange msg's body if usage constraint is given
     * At node <to>: unprotect exchange msg's body if usage constraing it fulfilled
     *
     * @param exchange The exchange object to check
     * @return Whether target.process() is to be called for this exchange object
     */

    private fun processFlowControl(exchange: Exchange?): Boolean {
        if (exchange == null) {
            if (LOG.isWarnEnabled) {
                LOG.warn("Cannot check data flow policy. Exchange object is null.")
            }
            return false
        }
        // Strict policy: If PDP or Usage Control interface are not available, block every checked data flow
        val pdp = CamelInterceptor.pdp
                ?: throw Exception("PDP is not available")
        val ucInterface = CamelInterceptor.usageControlInterface
                ?: throw Exception("Usage Control Interface is not available")

        // Save per exchange object the source node's content (<from: uri="idscp2server://0...>)
        // Same entry in Hashmap per CamelRoute, change of exchange properties do not create new entry
        val sourceNode = lastDestinations[exchange] ?: {
            // If there is no previously saved node for this exchange, use the parent <route> to find the input
            // (first <from> statement) of the processed route.
            var routeNode = destinationNode.parent
            while (routeNode !is RouteDefinition) {
                routeNode = routeNode.parent
            }
            routeNode.input
        }

        // Save current destination node of CamelRoute
        lastDestinations[exchange] = destinationNode
        val source = sourceNode.toString()
        val destination = destinationNode.toString()
        if (LOG.isTraceEnabled) {
            LOG.trace("{} -> {}", source, destination)
        }

        // Only take action for nodes of type <from> (= input) and <to> (= output)
        if (sourceNode is FromDefinition || destinationNode is ToDefinition) {
            val ucContract = try {
                ucInterface.getExchangeContract(exchange)
            } catch (x: RuntimeException) {
                // Thrown if data provider references an unknown ContractAgreement via transferContract
                throw Exception("Required contract is not available!", x)
            }
            // If there is no known ContractAgreement for this Exchange, nothing to do here.
            ucContract?.let { contract ->
                if (LOG.isDebugEnabled) {
                    LOG.debug("Applying Contract $contract")
                }
                val dockerConstraint = { c: Constraint ->
                    c.operator == BinaryOperator.SAME_AS && c.leftOperand == LeftOperand.SYSTEM }
                contract.permission.firstOrNull { p ->
                    // Check whether any constraint is given which fits the rules given above
                    p.constraint.firstOrNull(dockerConstraint) != null
                    // So far previous checks answered: "Can we principally work with given constraint?"
                }?.constraint?.first(dockerConstraint)?.rightOperandReference?.let { dockerUri ->
                    if (LOG.isDebugEnabled) {
                        LOG.debug("UC: Restricting to Container URI $dockerUri")
                    }
                    // Extracting hash and port of containerUri given by CamelRoute
                    val hashPart = dockerUri.path.split("/").last()
                    if (!hashPart.startsWith("sha256-")) {
                        throw Exception("Invalid docker URI for UC, last path component must start with \"sha256-\"!")
                    }
                    val hash = hashPart.substring(7)
                    val port = try {
                        dockerUri.fragment.toInt().also { assert(it in 1..65535) }
                    } catch (nfe: NumberFormatException) {
                        throw Exception("Invalid docker URI for UC, fragment must represent a valid port number!")
                    } catch (ae: AssertionError) {
                        throw Exception("Invalid docker URI for UC, ${dockerUri.fragment} is not a valid port number!")
                    }
                    // Check whether we deal with an entry node ("from:...") as source...
                    if (sourceNode is FromDefinition) {
                        // If we found an entry node, then protect exchange's body
                        ucInterface.protectBody(exchange, ucContract.id)
                    // ... or output ("to:...") node as destination of this transition.
                    // Additionally check whether exchange's body was protected.
                    } else if (destinationNode is ToDefinition && ucInterface.isProtected(exchange)) {
                        // Compare hash value and port of camelRoute's containerUri with local Docker containers
                        CamelInterceptor.containerManager?.let { cm ->
                            val endpointUri = URI.create(destinationNode.endpointUri)
                            // Check is containerUri's port matched CamelRoute ToNode's port
                            if (port != endpointUri.port) {
                                LOG.warn("UC: Exchange blocked by contract: " +
                                        "Port $port is permitted, but ${endpointUri.port} is used!")
                            } else {
                                val allowedContainers = cm.list(true).filter { container ->
                                    // From running docker containers get all with the given hash
                                    // Normally there is only one hash type, but there can be more
                                    // Currently requested type is only sha256 (e.g. sha3 or else may be added later)
                                    container.imageDigests.any { it.split(":").last() == hash }
                                }
                                // Save all ip addresses of allowed containers in one list
                                val allowedIPs = allowedContainers.flatMap { it.ipAddresses }.toSet()
                                // Check whether all endpoint's ip-addresses belong to allowed containers
                                if (InetAddress.getAllByName(endpointUri.host).all { allowedIPs.contains(it) }) {
                                    ucInterface.unprotectBody(exchange)
                                    if (LOG.isDebugEnabled) {
                                        LOG.debug("UC: Contract permits data flow, Exchange body has been restored.")
                                    }
                                } else {
                                    LOG.warn("UC: Some or all IP addresses of the host ${endpointUri.host} " +
                                            "do not belong to the permitted containers (${allowedContainers})")
                                }
                            }
                        } ?: LOG.warn("UC: ContainerManager is not available, " +
                                "cannot verify container-binding contract!")
                    }
                }
            }
        }

        val sourceServiceNode = ServiceNode(source, null, null)
        val destinationServiceNode = ServiceNode(destination, null, null)

        // Call PDP to transform labels and decide whether to forward the Exchange
        applyLabelTransformation(pdp.requestTranformations(sourceServiceNode), exchange)
        val labels = exchangeLabels.computeIfAbsent(exchange) { HashSet<String>() }
        val decision = pdp.requestDecision(
                DecisionRequest(sourceServiceNode, destinationServiceNode, labels, null))
        return when (decision.decision!!) {
            PolicyDecision.Decision.ALLOW -> true
            PolicyDecision.Decision.DENY -> {
                if (LOG.isWarnEnabled) {
                    LOG.warn("Exchange explicitly blocked (DENY) by data flow policy. " +
                            "Source: {}, Target: {}", sourceServiceNode, destinationServiceNode)
                }
                exchange.setException(Exception("Exchange blocked by data flow policy"))
                false
            }
        }
    }

    /**
     * Removes and adds labels to an exchange object.
     *
     * @param requestTransformations The request transformations (label changes) to be performed
     * @param exchange Exchange processed
     */
    private fun applyLabelTransformation(
            requestTransformations: TransformationDecision, exchange: Exchange) {
        val labels = exchangeLabels.computeIfAbsent(exchange) { HashSet<String>() }

        // Remove labels from exchange
        labels.removeAll(requestTransformations.labelsToRemove)

        // Add labels to exchange
        labels.addAll(requestTransformations.labelsToAdd)
    }

    @Throws(Exception::class)
    override fun process(exchange: Exchange) {
        if (processFlowControl(exchange)) {
            target.process(exchange)
        }
    }

    override fun process(exchange: Exchange, callback: AsyncCallback): Boolean {
        if (processFlowControl(exchange)) {
            try {
                target.process(exchange)
                callback.done(true)
                return true
            } catch (e: Exception) {
                LOG.error(e.message, e)
            }
        }
        callback.done(false)
        return false
    }

    override fun processAsync(exchange: Exchange): CompletableFuture<Exchange> {
        return try {
            target.process(exchange)
            CompletableFuture.completedFuture(exchange)
        } catch (x: Exception) {
            CompletableFuture.failedFuture(x)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(PolicyEnforcementPoint::class.java)
        private val lastDestinations: MutableMap<Exchange, NamedNode> =
                MapMaker().weakKeys().makeMap()
        private val exchangeLabels: MutableMap<Exchange, MutableSet<String>> =
                MapMaker().weakKeys().makeMap()
    }
}