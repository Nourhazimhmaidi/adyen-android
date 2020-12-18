/*
 * Copyright (c) 2019 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by caiof on 25/7/2019.
 */

package com.adyen.checkout.googlepay;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;

import com.adyen.checkout.components.ComponentAvailableCallback;
import com.adyen.checkout.components.PaymentComponentProvider;
import com.adyen.checkout.components.base.GenericPaymentMethodDelegate;
import com.adyen.checkout.components.base.lifecycle.PaymentComponentViewModelFactory;
import com.adyen.checkout.components.model.paymentmethods.PaymentMethod;
import com.adyen.checkout.googlepay.util.GooglePayUtils;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wallet.IsReadyToPayRequest;
import com.google.android.gms.wallet.PaymentsClient;
import com.google.android.gms.wallet.Wallet;

import java.lang.ref.WeakReference;

public class GooglePayProvider implements PaymentComponentProvider<GooglePayComponent, GooglePayConfiguration> {

    @SuppressWarnings("LambdaLast")
    @Override
    @NonNull
    public GooglePayComponent get(
            @NonNull ViewModelStoreOwner viewModelStoreOwner,
            @NonNull PaymentMethod paymentMethod,
            @NonNull GooglePayConfiguration configuration) {
        final PaymentComponentViewModelFactory factory =
                new PaymentComponentViewModelFactory(new GenericPaymentMethodDelegate(paymentMethod), configuration);
        return new ViewModelProvider(viewModelStoreOwner, factory).get(GooglePayComponent.class);
    }

    @Override
    public void isAvailable(@NonNull Application applicationContext, final @NonNull PaymentMethod paymentMethod,
            final @NonNull GooglePayConfiguration configuration, @NonNull ComponentAvailableCallback<GooglePayConfiguration> callback) {

        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(applicationContext) != ConnectionResult.SUCCESS) {
            callback.onAvailabilityResult(false, paymentMethod, configuration);
            return;
        }

        final WeakReference<ComponentAvailableCallback<GooglePayConfiguration>> callbackWeakReference = new WeakReference<>(callback);

        final PaymentsClient paymentsClient = Wallet.getPaymentsClient(applicationContext, GooglePayUtils.createWalletOptions(configuration));
        final IsReadyToPayRequest readyToPayRequest = GooglePayUtils.createIsReadyToPayRequest(configuration);

        final Task<Boolean> readyToPayTask =  paymentsClient.isReadyToPay(readyToPayRequest);

        readyToPayTask.addOnCompleteListener(new OnCompleteListener<Boolean>() {
            @Override
            public void onComplete(@NonNull Task<Boolean> task) {
                if (callbackWeakReference.get() != null) {
                    final boolean result = task.getResult() != null && task.getResult();
                    callbackWeakReference.get().onAvailabilityResult(result, paymentMethod, configuration);
                }
            }
        });

    }
}