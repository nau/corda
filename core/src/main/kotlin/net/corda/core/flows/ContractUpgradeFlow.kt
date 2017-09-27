package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.internal.ContractUpgradeUtils
import net.corda.core.internal.UpgradeCommand
import net.corda.core.transactions.TransactionBuilder

/**
 * A flow to be used for authorising and upgrading state objects of an old contract to a new contract.
 *
 * This assembles the transaction for contract upgrade and sends out change proposals to all participants
 * of that state. If participants agree to the proposed change, they each sign the transaction.
 * Finally, the transaction containing all signatures is sent back to each participant so they can record it and
 * use the new updated state for future transactions.
 */
object ContractUpgradeFlow {
    /**
     * Authorise a contract state upgrade.
     *
     * This will store the upgrade authorisation in persistent store, and will be queried by [ContractUpgradeFlow.Acceptor]
     * during contract upgrade process. Invoking this flow indicates the node is willing to upgrade the [StateAndRef] using
     * the [UpgradedContract] class.
     *
     * This flow will NOT initiate the upgrade process. To start the upgrade process, see [Initiate].
     */
    @StartableByRPC
    class Authorise(
            val stateAndRef: StateAndRef<*>,
            private val upgradedContractClass: Class<out UpgradedContract<*, *>>
        ) : FlowLogic<Void?>() {
        @Suspendable
        override fun call(): Void? {
            val upgrade = upgradedContractClass.newInstance()
            if (upgrade.legacyContract != stateAndRef.state.contract) {
                throw FlowException("The contract state cannot be upgraded using provided UpgradedContract.")
            }
            serviceHub.contractUpgradeService.storeAuthorisedContractUpgrade(stateAndRef.ref, upgradedContractClass)
            return null
        }

    }

    /**
     * Deauthorise a contract state upgrade.
     * This will remove the upgrade authorisation from persistent store (and prevent any further upgrade)
     */
    @StartableByRPC
    class Deauthorise(val stateRef: StateRef) : FlowLogic<Void?>() {
        @Suspendable
        override fun call(): Void? {
            serviceHub.contractUpgradeService.removeAuthorisedContractUpgrade(stateRef)
            return null
        }
    }

    /**
     * This flow begins the contract upgrade process.
     */
    @InitiatingFlow
    @StartableByRPC
    class Initiate<OldState : ContractState, out NewState : ContractState>(
            originalState: StateAndRef<OldState>,
            newContractClass: Class<out UpgradedContract<OldState, NewState>>
    ) : AbstractStateReplacementFlow.Instigator<OldState, NewState, Class<out UpgradedContract<OldState, NewState>>>(originalState, newContractClass) {

        companion object {
            fun <OldState : ContractState, NewState : ContractState> assembleBareTx(
                    stateRef: StateAndRef<OldState>,
                    upgradedContractClass: Class<out UpgradedContract<OldState, NewState>>,
                    privacySalt: PrivacySalt
            ): TransactionBuilder {
                val contractUpgrade = upgradedContractClass.newInstance()
                return TransactionBuilder(stateRef.state.notary)
                        .withItems(
                                stateRef,
                                StateAndContract(contractUpgrade.upgrade(stateRef.state.data), upgradedContractClass.name),
                                Command(UpgradeCommand(upgradedContractClass.name), stateRef.state.data.participants.map { it.owningKey }),
                                privacySalt
                        )
            }
        }

        @Suspendable
        override fun assembleTx(): AbstractStateReplacementFlow.UpgradeTx {
            val baseTx = ContractUpgradeUtils.assembleBareTx(originalState, modification, PrivacySalt())
            val participantKeys = originalState.state.data.participants.map { it.owningKey }.toSet()
            // TODO: We need a much faster way of finding our key in the transaction
            val myKey = serviceHub.keyManagementService.filterMyKeys(participantKeys).single()
            val stx = serviceHub.signInitialTransaction(baseTx, myKey)
            return AbstractStateReplacementFlow.UpgradeTx(stx)
        }
    }
}
