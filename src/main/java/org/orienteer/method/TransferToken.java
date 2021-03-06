package org.orienteer.method;

import com.orientechnologies.orient.core.record.impl.ODocument;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.ajax.markup.html.modal.ModalWindow;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.ResourceModel;
import org.orienteer.component.InfoMessagePanel;
import org.orienteer.component.token.TransferTokenPanel;
import org.orienteer.core.component.BootstrapType;
import org.orienteer.core.component.FAIconType;
import org.orienteer.core.component.command.AbstractModalWindowCommand;
import org.orienteer.core.method.OFilter;
import org.orienteer.core.method.OMethod;
import org.orienteer.core.method.filters.ODocumentFilter;
import org.orienteer.core.method.filters.PlaceFilter;
import org.orienteer.core.method.methods.AbstractModalOMethod;
import org.orienteer.method.filter.ODocumentExistsFilter;
import org.orienteer.model.Wallet;

@OMethod(
		order = 2,
		icon = FAIconType.exchange,
		bootstrap = BootstrapType.WARNING,
		titleKey = "method.token.transfer",
		filters = {
			@OFilter(fClass = ODocumentFilter.class, fData = Wallet.CLASS_NAME),
			@OFilter(fClass = PlaceFilter.class, fData = "STRUCTURE_TABLE"),
			@OFilter(fClass = ODocumentExistsFilter.class, fData = "true")
		}
)
public class TransferToken extends AbstractModalOMethod {

	@Override
	public Component getModalContent(String componentId, ModalWindow modal,AbstractModalWindowCommand<?> command) {
		modal.setMinimalWidth(370);
		modal.setAutoSize(true);

		return new TransferTokenPanel(componentId, Model.of(getWallet()), Model.of()) {
			@Override
			protected void onTransferTokens(AjaxRequestTarget target) {
				modal.setContent(new InfoMessagePanel(modal.getContentId(), new ResourceModel("transfer.token.success.text")) {
					@Override
					protected void onOkClick(AjaxRequestTarget target) {
						modal.close(target);
					}
				});
				modal.setTitle(new ResourceModel("info.title"));
				modal.close(target);
				modal.show(target);
				command.onAfterModalSubmit();
			}
		};
	}

	protected Wallet getWallet() {
		ODocument walletDoc = (ODocument) getContext().getDisplayObjectModel().getObject();
		return new Wallet(walletDoc);
	}

}