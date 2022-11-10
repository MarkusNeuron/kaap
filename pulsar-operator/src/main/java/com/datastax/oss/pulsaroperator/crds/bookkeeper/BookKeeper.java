package com.datastax.oss.pulsaroperator.crds.bookkeeper;

import com.datastax.oss.pulsaroperator.crds.CRDConstants;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Version(CRDConstants.VERSION)
@Group(CRDConstants.GROUP)
@Kind("BookKeeper")
@Singular("bookkeeper")
@Plural("bookkeepers")
@ShortNames({"bk"})
public class BookKeeper extends CustomResource<BookKeeperFullSpec, BookKeeperStatus> implements Namespaced {
    @Override
    protected BookKeeperStatus initStatus() {
        return new BookKeeperStatus();
    }
}
