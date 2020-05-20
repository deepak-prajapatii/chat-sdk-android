package sdk.chat.app.firestream.test.contact;

import java.util.List;

import firestream.chat.chat.User;
import firestream.chat.namespace.Fire;
import sdk.chat.app.firestream.test.Result;
import sdk.chat.app.firestream.test.Test;
import sdk.chat.app.firestream.test.TestScript;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import sdk.guru.common.RX;

public class DeleteContactTest extends Test {

    public DeleteContactTest() {
        super("DeleteContact");
    }

    @Override
    public Observable<Result> run() {
        return Observable.create((ObservableOnSubscribe<Result>) emitter -> {
            // Remove the contact
            manage(emitter);
            dm.add(Fire.stream().removeContact(TestScript.testUser1()).subscribe(() -> {
                // Check that it exists in the contact list
                List<User> contacts = Fire.stream().getContacts();

                if (contacts.size() != 0) {
                    failure("Contact size must be 0");
                } else {
                    complete();
                }

            }, this));
        }).subscribeOn(RX.io());
    }}
