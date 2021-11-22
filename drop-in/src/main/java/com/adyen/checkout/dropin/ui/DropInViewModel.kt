/*
 * Copyright (c) 2019 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by arman on 2/7/2019.
 */

package com.adyen.checkout.dropin.ui

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.adyen.checkout.components.model.PaymentMethodsApiResponse
import com.adyen.checkout.components.model.paymentmethods.StoredPaymentMethod
import com.adyen.checkout.components.model.payments.Amount
import com.adyen.checkout.components.model.payments.request.PaymentMethodDetails
import com.adyen.checkout.components.model.payments.response.BalanceResult
import com.adyen.checkout.components.util.PaymentMethodTypes
import com.adyen.checkout.core.exception.CheckoutException
import com.adyen.checkout.core.log.LogUtil
import com.adyen.checkout.core.log.Logger
import com.adyen.checkout.dropin.DropInConfiguration
import com.adyen.checkout.dropin.R
import com.adyen.checkout.dropin.ui.giftcard.GiftCardBalanceResult
import com.adyen.checkout.dropin.ui.giftcard.GiftCardPaymentConfirmationData
import com.adyen.checkout.giftcard.GiftCardComponentState
import com.adyen.checkout.giftcard.util.GiftCardBalanceStatus
import com.adyen.checkout.giftcard.util.GiftCardBalanceUtils
import com.adyen.checkout.googlepay.GooglePayComponent
import org.json.JSONException
import org.json.JSONObject

private val TAG = LogUtil.getTag()

private const val PAYMENT_METHODS_RESPONSE_KEY = "PAYMENT_METHODS_RESPONSE_KEY"
private const val DROP_IN_CONFIGURATION_KEY = "DROP_IN_CONFIGURATION_KEY"
private const val DROP_IN_RESULT_INTENT_KEY = "DROP_IN_RESULT_INTENT_KEY"
private const val IS_WAITING_FOR_RESULT_KEY = "IS_WAITING_FOR_RESULT_KEY"
private const val CACHED_GIFT_CARD = "CACHED_GIFT_CARD"

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

    var cachedGiftCardComponentState: GiftCardComponentState?
        get() {
            return savedStateHandle.get<GiftCardComponentState>(CACHED_GIFT_CARD)
        }
        private set(value) {
            savedStateHandle[CACHED_GIFT_CARD] = value
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

    /**
     * @return the payment method details required to request the balance, or null if invalid
     */
    fun onBalanceCallRequested(giftCardComponentState: GiftCardComponentState): PaymentMethodDetails? {
        val paymentMethod = giftCardComponentState.data.paymentMethod
        if (paymentMethod == null) {
            Logger.e(TAG, "onBalanceCallRequested - paymentMethod is null")
            return null
        }
        cachedGiftCardComponentState = giftCardComponentState
        return paymentMethod
    }

    fun handleBalanceResult(balanceJson: String): GiftCardBalanceResult {
        val balanceJSONObject = try {
            JSONObject(balanceJson)
        } catch (e: JSONException) {
            throw CheckoutException("Provided balance is not a JSON object")
        }
        val balanceResult = BalanceResult.SERIALIZER.deserialize(balanceJSONObject)
        Logger.d(TAG, "handleBalanceResult - balance: ${balanceResult.balance} - transactionLimit: ${balanceResult.transactionLimit}")

        val balance = Amount.SERIALIZER.deserialize(JSONObject(balanceResult.balance))
        val transactionLimitString = balanceResult.transactionLimit
        val transactionLimit =
            if (transactionLimitString == null) null
            else Amount.SERIALIZER.deserialize(JSONObject(transactionLimitString))
        val giftCardBalanceResult = GiftCardBalanceUtils.checkBalance(
            balance = balance,
            transactionLimit = transactionLimit,
            amountToBePaid = dropInConfiguration.amount
        )
        val cachedGiftCardComponentState = cachedGiftCardComponentState ?: throw CheckoutException("Failed to retrieved cached gift card object")
        return when (giftCardBalanceResult) {
            is GiftCardBalanceStatus.ZeroBalance -> {
                Logger.i(TAG, "handleBalanceResult - Gift Card has zero balance")
                GiftCardBalanceResult.Error(R.string.checkout_giftcard_error_zero_balance, "Gift Card has zero balance", false)
            }
            is GiftCardBalanceStatus.NonMatchingCurrencies -> {
                Logger.e(TAG, "handleBalanceResult - Gift Card currency mismatch")
                GiftCardBalanceResult.Error(R.string.checkout_giftcard_error_currency, "Gift Card currency mismatch", false)
            }
            is GiftCardBalanceStatus.ZeroAmountToBePaid -> {
                Logger.e(TAG, "handleBalanceResult - You must set an amount in DropInConfiguration.Builder to enable gift card payments")
                GiftCardBalanceResult.Error(R.string.payment_failed, "Drop-in amount is not set", true)
            }
            is GiftCardBalanceStatus.FullPayment -> {
                GiftCardBalanceResult.FullPayment(
                    createGiftCardPaymentConfirmationData(giftCardBalanceResult, cachedGiftCardComponentState)
                )
            }
            is GiftCardBalanceStatus.PartialPayment -> {
                GiftCardBalanceResult.PartialPayment(giftCardBalanceResult.amountPaid, giftCardBalanceResult.remainingBalance)
            }
        }
    }

    private fun createGiftCardPaymentConfirmationData(
        giftCardBalanceStatus: GiftCardBalanceStatus.FullPayment,
        giftCardComponentState: GiftCardComponentState
    ): GiftCardPaymentConfirmationData {
        return GiftCardPaymentConfirmationData(
            amountPaid = giftCardBalanceStatus.amountPaid,
            remainingBalance = giftCardBalanceStatus.remainingBalance,
            shopperLocale = dropInConfiguration.shopperLocale,
            brand = giftCardComponentState.data.paymentMethod?.brand.orEmpty(),
            lastFourDigits = giftCardComponentState.lastFourDigits.orEmpty()
        )
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
