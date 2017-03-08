package org.daisy.dotify.formatter.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class PageAreaContent {
	private final List<RowImpl> before;
	private final List<RowImpl> after;

	PageAreaContent(PageAreaBuilderImpl pab, BlockContext bc) {
		if (pab !=null) {
			//Assumes before is static
			this.before = Collections.unmodifiableList(renderRows(pab.getBeforeArea(), bc));

			//Assumes after is static
			this.after = Collections.unmodifiableList(renderRows(pab.getAfterArea(), bc));
		} else {
			this.before = Collections.emptyList();
			this.after = Collections.emptyList();
		}
	}

	private static List<RowImpl> renderRows(Iterable<Block> blocks, BlockContext bc) {
		List<RowImpl> ret = new ArrayList<>();
		for (Block b : blocks) {
			AbstractBlockContentManager bcm = b.getBlockContentManager(bc);
			for (int i=0; i<bcm.getRowCount(); i++) {
				ret.add(bcm.get(i));
			}
		}
		return ret;
	}
	
	List<RowImpl> getBefore() {
		return before;
	}

	List<RowImpl> getAfter() {
		return after;
	}

}
