package org.orienteer.util;

import com.google.common.base.Strings;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.ResourceModel;
import org.apache.wicket.validation.IValidatable;
import org.apache.wicket.validation.IValidator;
import org.apache.wicket.validation.ValidationError;
import org.orienteer.core.OrienteerWebApplication;
import org.orienteer.model.Token;
import org.orienteer.model.Wallet;
import org.orienteer.module.ICOFarmModule;
import org.orienteer.service.web3.IEthereumService;
import rx.Single;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.orienteer.util.ICOFarmUtils.toWei;

public class BuyTokensTransactionValidator implements IValidator<String> {

    private final IModel<Wallet> walletModel;
    private final IModel<Token> currencyModel;
    private final IModel<Token> tokenModel;

    public BuyTokensTransactionValidator(IModel<Wallet> walletModel, IModel<Token> currencyModel, IModel<Token> tokenModel) {
        this.walletModel = walletModel;
        this.currencyModel = currencyModel;
        this.tokenModel = tokenModel;
    }

    @Override
    public void validate(IValidatable<String> validatable) {
        if (walletModel.getObject() != null && currencyModel.getObject() != null && tokenModel.getObject() != null) {
            BigDecimal value = getValueFromString(validatable.getValue());
            if (value != null) {
                validate(value, validatable);
            }
        }
    }

    private void validate(BigDecimal value, IValidatable<String> validatable) {
        IEthereumService ethService = OrienteerWebApplication.lookupApplication().getServiceInstance(IEthereumService.class);
        BigInteger wei = toWei(value, currencyModel.getObject());
        BigInteger walletWei = walletModel.getObject().getBalance(ICOFarmModule.WEI).toBigInteger();
        BigInteger delta = walletWei.subtract(wei);
        int compared = delta.compareTo(BigInteger.ZERO);
        if (compared > 0) {
            getGasInWeiForBuy(ethService, wei)
                .subscribe(
                    gasInWei -> {
                        if (delta.subtract(gasInWei).compareTo(BigInteger.ZERO) < 0) {
                            error(validatable, "validator.transaction.buy.gas.not.enough.money");
                        }
                    },
                    t -> {
                        t.printStackTrace();
                        error(validatable, "validator.transaction.error");
                    }
            );
        } else if (compared == 0) {
            error(validatable, "validator.transaction.buy.gas.not.enough.money");
        } else error(validatable, "validator.transaction.buy.not.enough.money");
    }

    private BigDecimal getValueFromString(String value) {
        BigDecimal result = null;
        if (!Strings.isNullOrEmpty(value)) {
            try {
                result = new BigDecimal(value);
            } catch (NumberFormatException ex) {}
        }
        return result;
    }

    private Single<BigInteger> getGasInWeiForBuy(IEthereumService ethService, BigInteger weiAmount) {
        return ethService.loadSmartContract(walletModel.getObject().getAddress(), tokenModel.getObject())
                .estimateGasForBuy(weiAmount)
                .flatMap(gas -> ethService.getGasPrice().map(gas::multiply));
    }


    private void error(IValidatable<String> validatable, String key) {
        ValidationError err = new ValidationError();
        err.setMessage(new ResourceModel(key).getObject());
        validatable.error(err);
    }
}
