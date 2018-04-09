package org.orienteer;

import com.google.inject.Inject;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.orienteer.junit.OrienteerTestRunner;
import org.orienteer.model.EmbeddedWallet;
import org.orienteer.service.IUpdateWalletService;
import ru.ydn.wicket.wicketorientdb.utils.DBClosure;

import java.math.BigInteger;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertTrue;

@RunWith(OrienteerTestRunner.class)
public class TestUpdateService {

    @Inject
    private IUpdateWalletService updateService;

    private List<EmbeddedWallet> wallets;

    @Before
    public void init() {
        wallets = new LinkedList<>();
        wallets.add((EmbeddedWallet) new EmbeddedWallet().sudoSave());
        wallets.add((EmbeddedWallet) new EmbeddedWallet().sudoSave());
        wallets.add((EmbeddedWallet) new EmbeddedWallet().sudoSave());
    }

    @After
    public void destroy() {
        wallets.forEach(wallet ->
                DBClosure.sudoConsumer((db) -> db.command(new OCommandSQL("delete from ?")).execute(wallet.getDocument()))
        );
    }

    @Test
    public void testUpdateWallets() {
        updateService.updateBalance(wallets, (balance) -> assertTrue(balance.equals(BigInteger.ZERO)));
    }
}
