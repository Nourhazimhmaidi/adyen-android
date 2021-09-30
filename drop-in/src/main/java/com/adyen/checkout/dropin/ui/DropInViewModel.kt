/*
 * Copyright (c) 2019 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by arman on 2/7/2019.
 */

package com.adyen.checkout.dropin.ui

import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.adyen.checkout.components.model.PaymentMethodsApiResponse
import com.adyen.checkout.components.model.paymentmethods.StoredPaymentMethod
import com.adyen.checkout.components.model.payments.Amount
import com.adyen.checkout.components.util.PaymentMethodTypes
import com.adyen.checkout.core.exception.CheckoutException
import com.adyen.checkout.core.log.LogUtil
import com.adyen.checkout.core.log.Logger
import com.adyen.checkout.dropin.DropInConfiguration
import com.adyen.checkout.dropin.R
import com.adyen.checkout.dropin.service.BalanceResult
import com.adyen.checkout.giftcard.util.GiftCardBalanceResult
import com.adyen.checkout.giftcard.util.GiftCardBalanceUtils
import com.adyen.checkout.googlepay.GooglePayComponent
import org.json.JSONObject

private val TAG = LogUtil.getTag()

private const val PAYMENT_METHODS_RESPONSE_KEY = "PAYMENT_METHODS_RESPONSE_KEY"
private const val DROP_IN_CONFIGURATION_KEY = "DROP_IN_CONFIGURATION_KEY"
private const val DROP_IN_RESULT_INTENT_KEY = "DROP_IN_RESULT_INTENT_KEY"
private const val IS_WAITING_FOR_RESULT_KEY = "IS_WAITING_FOR_RESULT_KEY"

class DropInViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    val paymentMethodsApiResponse: PaymentMethodsApiResponse = getStateValueOrFail(PAYMENT_METHODS_RESPONSE_KEY)
    val dropInConfiguration: DropInConfiguration = getStateValueOrFail(DROP_IN_CONFIGURATION_KEY)
    val resultHandlerIntent: Intent? = savedStateHandle[DROP_IN_RESULT_INTENT_KEY]

    var isWaitingResult: Boolean
        get() {
            return savedStateHandle[IS_WAITING_FOR_RESULT_KEY] ?: false
        }
        set(value) {
            savedStateHandle[IS_WAITING_FOR_RESULT_KEY] = value
        }

    private fun <T> getStateValueOrFail(key: String): T {
        val value: T? = savedStateHandle[key]
        if (value == null) {
            Logger.e(TAG, "Failed to initialize bundle from SavedStateHandle")
            throw CheckoutException("Failed to initialize Drop-in, did you manually launch DropInActivity?")
        }
        return value
    }

    val showPreselectedStored = paymentMethodsApiResponse.storedPaymentMethods?.any { it.isEcommerce } == true &&
        dropInConfiguration.showPreselectedStoredPaymentMethod
    val preselectedStoredPayment = paymentMethodsApiResponse.storedPaymentMethods?.firstOrNull {
        it.isEcommerce && PaymentMethodTypes.SUPPORTED_PAYMENT_METHODS.contains(it.type)
    } ?: StoredPaymentMethod()

    fun getStoredPaymentMethod(id: String): StoredPaymentMethod {
        return paymentMethodsApiResponse.storedPaymentMethods?.firstOrNull { it.id == id } ?: StoredPaymentMethod()
    }

    fun shouldSkipToSinglePaymentMethod(): Boolean {
        val noStored = paymentMethodsApiResponse.storedPaymentMethods.isNullOrEmpty()
        val singlePm = paymentMethodsApiResponse.paymentMethods?.size == 1

        val firstPaymentMethod = paymentMethodsApiResponse.paymentMethods?.firstOrNull()
        val paymentMethodHasComponent = PaymentMethodTypes.SUPPORTED_PAYMENT_METHODS.contains(firstPaymentMethod?.type) &&
            !GooglePayComponent.PAYMENT_METHOD_TYPES.contains(firstPaymentMethod?.type) &&
            !PaymentMethodTypes.SUPPORTED_ACTION_ONLY_PAYMENT_METHODS.contains(firstPaymentMethod?.type)

        return noStored && singlePm && paymentMethodHasComponent && dropInConfiguration.skipListWhenSinglePaymentMethod
    }

    fun handleBalanceResult(balanceResult: BalanceResult): GiftCardResult {
        Logger.d(TAG, "handleBalanceResult - balance: ${balanceResult.balance} - transactionLimit: ${balanceResult.transactionLimit}")
        val balance = Amount.SERIALIZER.deserialize(JSONObject(balanceResult.balance))
        val transactionLimit =
            if (balanceResult.transactionLimit == null) null
            else Amount.SERIALIZER.deserialize(JSONObject(balanceResult.transactionLimit))
        val giftCardBalanceResult = GiftCardBalanceUtils.checkBalance(
            balance = balance,
            transactionLimit = transactionLimit,
            amountToBePaid = dropInConfiguration.amount
        )
        return when (giftCardBalanceResult) {
            is GiftCardBalanceResult.ZeroBalance -> {
                Logger.i(TAG, "handleBalanceResult - Gift Card has zero balance")
                GiftCardResult.Error(R.string.checkout_giftcard_error_zero_balance, "Gift Card has zero balance", false)
            }
            is GiftCardBalanceResult.NonMatchingCurrencies -> {
                Logger.e(TAG, "handleBalanceResult - Gift Card currency mismatch")
                GiftCardResult.Error(R.string.checkout_giftcard_error_currency, "Gift Card currency mismatch", false)
            }
            is GiftCardBalanceResult.ZeroAmountToBePaid -> {
                Logger.e(TAG, "handleBalanceResult - You must set an amount in DropInConfiguration.Builder to enable gift card payments")
                GiftCardResult.Error(R.string.payment_failed, "Drop-in amount is not set", true)
            }
            is GiftCardBalanceResult.FullPayment -> {
                GiftCardResult.FullPayment(giftCardBalanceResult.amountPaid, giftCardBalanceResult.remainingBalance)
            }
            is GiftCardBalanceResult.PartialPayment -> {
                GiftCardResult.PartialPayment(giftCardBalanceResult.amountPaid, giftCardBalanceResult.remainingBalance)
            }
        }
    }

    companion object {
        fun putIntentExtras(
            intent: Intent,
            dropInConfiguration: DropInConfiguration,
            paymentMethodsApiResponse: PaymentMethodsApiResponse,
            resultHandlerIntent: Intent?
        ) {
            intent.apply {
                putExtra(PAYMENT_METHODS_RESPONSE_KEY, paymentMethodsApiResponse)
                putExtra(DROP_IN_CONFIGURATION_KEY, dropInConfiguration)
                putExtra(DROP_IN_RESULT_INTENT_KEY, resultHandlerIntent)
            }
        }
    }
}

sealed class GiftCardResult {
    class FullPayment(val amountPaid: Amount, val remainingBalance: Amount) : GiftCardResult()
    class PartialPayment(val amountPaid: Amount, val remainingBalance: Amount) : GiftCardResult()
    class Error(@StringRes val errorMessage: Int, val reason: String, val terminateDropIn: Boolean) : GiftCardResult()
}
