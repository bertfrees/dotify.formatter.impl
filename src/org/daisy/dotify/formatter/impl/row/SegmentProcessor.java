package org.daisy.dotify.formatter.impl.row;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.daisy.dotify.api.formatter.Context;
import org.daisy.dotify.api.formatter.FormattingTypes;
import org.daisy.dotify.api.formatter.Marker;
import org.daisy.dotify.api.translator.BrailleTranslatorResult;
import org.daisy.dotify.api.translator.Translatable;
import org.daisy.dotify.api.translator.TranslationException;
import org.daisy.dotify.common.text.StringTools;
import org.daisy.dotify.formatter.impl.common.FormatterCoreContext;
import org.daisy.dotify.formatter.impl.row.RowImpl.Builder;
import org.daisy.dotify.formatter.impl.search.CrossReferenceHandler;
import org.daisy.dotify.formatter.impl.search.DefaultContext;
import org.daisy.dotify.formatter.impl.segment.AnchorSegment;
import org.daisy.dotify.formatter.impl.segment.Evaluate;
import org.daisy.dotify.formatter.impl.segment.LeaderSegment;
import org.daisy.dotify.formatter.impl.segment.MarkerSegment;
import org.daisy.dotify.formatter.impl.segment.PageNumberReferenceSegment;
import org.daisy.dotify.formatter.impl.segment.Segment;
import org.daisy.dotify.formatter.impl.segment.TextSegment;

class SegmentProcessor implements SegmentProcessing {
	private static final Pattern softHyphenPattern  = Pattern.compile("\u00ad");
	private static final Pattern trailingWsBraillePattern = Pattern.compile("[\\s\u2800]+\\z");
	private final List<Segment> segments;
	private final CrossReferenceHandler refs;
	private Context context;
	private final boolean significantContent;
	private final SegmentProcessorContext spc;

	private int segmentIndex;
	private RowImpl.Builder currentRow;
	private final ArrayList<Marker> groupMarkers;
	private final ArrayList<String> groupAnchors;
	private AggregatedBrailleTranslatorResult.Builder layoutOrApplyAfterLeader;
	private String currentLeaderMode;
	private boolean seenSegmentAfterLeader;
	private final LeaderManager leaderManager;
	private ListItem item;
	private int forceCount;
	private int minLeft;
	private int minRight;
	private boolean empty;
	private CurrentResult cr;
	private boolean closed;

	SegmentProcessor(List<Segment> segments, int flowWidth, CrossReferenceHandler refs, Context context, int available, BlockMargin margins, FormatterCoreContext fcontext, RowDataProperties rdp) {
		this.segments = Collections.unmodifiableList(segments);
		this.refs = refs;
		this.context = context;
		this.groupMarkers = new ArrayList<>();
		this.groupAnchors = new ArrayList<>();
		this.leaderManager = new LeaderManager();
		this.significantContent = calculateSignificantContent(segments, context, rdp);
		this.spc = new SegmentProcessorContext(fcontext, rdp, margins, flowWidth, available);
		initFields();
	}
	
	SegmentProcessor(SegmentProcessor template) {
		// Refs is mutable, but for now we assume that the same context should be used.
		this.refs = template.refs;
		// Context is mutable, but for now we assume that the same context should be used.
		this.context = template.context;
		this.spc = template.spc;
		this.currentRow = template.currentRow==null?null:new RowImpl.Builder(template.currentRow);
		this.groupAnchors = new ArrayList<>(template.groupAnchors);
		this.groupMarkers = new ArrayList<>(template.groupMarkers);
		this.leaderManager = new LeaderManager(template.leaderManager);
		this.layoutOrApplyAfterLeader = template.layoutOrApplyAfterLeader==null?null:new AggregatedBrailleTranslatorResult.Builder(template.layoutOrApplyAfterLeader);
		this.currentLeaderMode = template.currentLeaderMode;
		this.seenSegmentAfterLeader = template.seenSegmentAfterLeader;
		this.item = template.item;
		this.forceCount = template.forceCount;
		this.minLeft = template.minLeft;
		this.minRight = template.minRight;
		this.empty  = template.empty;
		this.segments = template.segments;
		this.segmentIndex = template.segmentIndex;
		this.cr = template.cr!=null?template.cr.copy():null;
		this.closed = template.closed;
		this.significantContent = template.significantContent;
	}
	
	private static boolean calculateSignificantContent(List<Segment> segments, Context context, RowDataProperties rdp) {
		for (Segment s : segments) {
			switch (s.getSegmentType()) {
				case Marker:
				case Anchor:
					// continue
					break;
				case Evaluate:
					if (!((Evaluate)s).getExpression().render(context).isEmpty()) {
						return true;
					}
					break;
				case Text:
					if (!((TextSegment)s).getText().isEmpty()) {
						return true;
					}
					break;
				case NewLine:
				case Leader:
				case Reference:
				default:
					return true;
			}
		}
		return rdp.getUnderlineStyle()!=null;
	}

	private void initFields() {
		segmentIndex = 0;
		currentRow = null;
		leaderManager.discardAllLeaders();
		layoutOrApplyAfterLeader = null;
		currentLeaderMode = null;
		seenSegmentAfterLeader = false;
		item = spc.getRdp().getListItem();
		minLeft = spc.getFlowWidth();
		minRight = spc.getFlowWidth();
		empty = true;
		cr = null;
		closed = false;
		// produce group markers and anchors
		getNext(false);
	}

	boolean couldTriggerNewRow() {
		if (!hasSegments()) {
			//There's a lot of conditions to keep track of here, but hopefully we can simplify later on
			return !closed && (currentRow!=null || !empty && spc.getRdp().getUnderlineStyle()!=null || leaderManager.hasLeader());
		}
		Segment s = segments.get(segmentIndex);
		switch (s.getSegmentType()) {
			case Marker:
			case Anchor:
				return false;
			case Evaluate:
				return !((Evaluate)s).getExpression().render(context).isEmpty();
			case Text:
				return !((TextSegment)s).getText().isEmpty();
			default:
				return true;
		}
	}

	boolean hasMoreData() {
		return hasSegments() || !closed || cr!=null && cr.hasNext(this);
	}
	
	private boolean hasSegments() {
		return segmentIndex<segments.size();
	}

	void prepareNext() {
		if (!hasMoreData()) {
			throw new IllegalStateException();
		}
		if (cr == null) {
			if (!hasSegments() && !closed) {
				closed = true;
				cr = new CloseResult(spc, layoutLeader());
			} else {
				cr = loadNextSegment().orElse(null);
			}
		}
	}
	
	boolean hasNext() {
		return cr!=null && cr.hasNext(this);
	}
	
	public boolean hasSignificantContent() {
		return significantContent;
	}
	
	Optional<RowImpl> getNext() {
		return getNext(true);
	}

	private Optional<RowImpl> getNext(boolean produceRow) {
		while (true) {
			if (cr!=null && cr.hasNext(this)) {
				try {
					Optional<RowImpl> ret = cr.process(this);
					if (ret.isPresent()) {
						if (!produceRow) {
							// there is a test below that verifies that the current segment cannot produce a row result
							// and the segment was processed under this assumption. If a row has been produced anyway, that's an error
							// in the code.
							throw new RuntimeException("Error in code");
						}
						return ret;
					} // else try the next segment.
				} finally {
					if (!cr.hasNext(this)) {
						cr = null;
					}
				}
			} else if (hasMoreData()) {
				if (!produceRow && couldTriggerNewRow()) {
					return Optional.empty();
				}
				prepareNext();
			} else {
				return Optional.empty();
			}
		}
	}

	private Optional<CurrentResult> loadNextSegment() {
		Segment s = segments.get(segmentIndex);
		segmentIndex++;
		switch (s.getSegmentType()) {
			case NewLine:
				//flush
				return Optional.of(new NewLineResult(spc, layoutLeader()));
			case Text:
				return layoutTextSegment((TextSegment)s);
			case Leader:
				return layoutLeaderSegment((LeaderSegment)s);
			case Reference:
				return layoutPageSegment((PageNumberReferenceSegment)s);
			case Evaluate:
				return layoutEvaluate((Evaluate)s);
			case Marker:
				applyAfterLeader((MarkerSegment)s);
				return Optional.empty();
			case Anchor:
				applyAfterLeader((AnchorSegment)s);
				return Optional.empty();
			default:
				return Optional.empty();
		}
	}

	private static class CloseResult implements CurrentResult {
		private final SegmentProcessorContext spc;
		private Optional<CurrentResult> cr;
		private boolean doFlush;
		private boolean doUnderline;
		
		private CloseResult(SegmentProcessorContext spc, Optional<CurrentResult> cr) {
			this.spc = spc;
			this.cr = cr;
			this.doFlush = true;
			this.doUnderline = spc.getRdp().getUnderlineStyle()!=null;
		}
		
		private CloseResult(CloseResult template) {
			this.spc = template.spc;
			if (template.cr.isPresent()) {
				this.cr = Optional.of(template.cr.get().copy());
			}
			this.doFlush = template.doFlush;
			this.doUnderline = template.doUnderline;
		}

		@Override
		public boolean hasNext(SegmentProcessing spi) {
			return cr.isPresent() && cr.get().hasNext(spi) || doFlush || (!spi.isEmpty() && doUnderline);
		}

		@Override
		public Optional<RowImpl> process(SegmentProcessing spi) {
			if (cr.isPresent() && cr.get().hasNext(spi)) {
				return cr.get().process(spi);
			} else if (doFlush) {
				doFlush = false;
				if (spi.hasCurrentRow()) {
					return Optional.of(spi.flushCurrentRow());
				}
			} else if (!spi.isEmpty() && doUnderline) {
				doUnderline = false;
				if (spi.getUnusedLeft() < spc.getMargins().getLeftMargin().getContent().length() || spi.getUnusedRight() < spc.getMargins().getRightMargin().getContent().length()) {
					throw new RuntimeException("coding error");
				}
				return Optional.of(new RowImpl.Builder(StringTools.fill(spc.getSpaceCharacter(), spi.getUnusedLeft() - spc.getMargins().getLeftMargin().getContent().length())
							+ StringTools.fill(spc.getRdp().getUnderlineStyle(), spc.getFlowWidth() - spi.getUnusedLeft() - spi.getUnusedRight()))
							.leftMargin(spc.getMargins().getLeftMargin())
							.rightMargin(spc.getMargins().getRightMargin())
							.adjustedForMargin(true)
							.build());
			}
			return Optional.empty();
		}

		@Override
		public CurrentResult copy() {
			return new CloseResult(this);
		}
	}

	@Override
	public RowImpl flushCurrentRow() {
		if (empty) {
			// Clear group anchors and markers (since we have content, we don't need them)
			currentRow.addAnchors(0, groupAnchors);
			groupAnchors.clear();
			currentRow.addMarkers(0, groupMarkers);
			groupMarkers.clear();
		}
		RowImpl r = currentRow.build();
		empty = false;
		//Make calculations for underlining
		int width = r.getChars().length();
		int left = r.getLeftMargin().getContent().length();
		int right = r.getRightMargin().getContent().length();
		int space = spc.getFlowWidth() - width - left - right;
		left += r.getAlignment().getOffset(space);
		right = spc.getFlowWidth() - width - left;
		minLeft = Math.min(minLeft, left);
		minRight = Math.min(minRight, right);
		currentRow = null;
		return r;
	}
	
	private static class NewLineResult implements CurrentResult {
		private final SegmentProcessorContext spc;
		private boolean newLine;
		private Optional<CurrentResult> cr;

		private NewLineResult(SegmentProcessorContext spc, Optional<CurrentResult> cr) {
			this.spc = spc;
			this.cr = cr;
			this.newLine = true;
		}
		
		private NewLineResult(NewLineResult template) {
			this.spc = template.spc;
			if (template.cr.isPresent()) {
				this.cr = Optional.of(template.cr.get().copy());
			}
			this.newLine = template.newLine;
		}

		@Override
		public boolean hasNext(SegmentProcessing spi) {
			return cr.isPresent() && cr.get().hasNext(spi) || newLine;
		}

		@Override
		public Optional<RowImpl> process(SegmentProcessing spi) {
			if (cr.isPresent() && cr.get().hasNext(spi)) {
				return cr.get().process(spi);
			} else if (newLine) {
				newLine = false;
				try {
					if (spi.hasCurrentRow()) {
						return Optional.of(spi.flushCurrentRow());
					}
				} finally {
					MarginProperties ret = new MarginProperties(spc.getMargins().getLeftMargin().getContent()+StringTools.fill(spc.getSpaceCharacter(), spc.getRdp().getTextIndent()), spc.getMargins().getLeftMargin().isSpaceOnly());
					spi.newCurrentRow(ret, spc.getMargins().getRightMargin());
				}
			}
			return Optional.empty();
		}

		@Override
		public CurrentResult copy() {
			return new NewLineResult(this);
		}		
	}
	
	private Optional<CurrentResult> layoutTextSegment(TextSegment ts) {
		Translatable spec = Translatable.text(
						spc.getFormatterContext().getConfiguration().isMarkingCapitalLetters()?
						ts.getText():ts.getText().toLowerCase()
				)
				.locale(ts.getTextProperties().getLocale())
				.hyphenate(ts.getTextProperties().isHyphenating())
				.attributes(ts.getTextAttribute()).build();
		String mode = ts.getTextProperties().getTranslationMode();
		if (leaderManager.hasLeader()) {
			layoutAfterLeader(spec, mode);
		} else {
			BrailleTranslatorResult btr = toResult(spec, mode);
			CurrentResult cr = new CurrentResultImpl(spc, btr, mode);
			return Optional.of(cr);
		}
		return Optional.empty();
	}
	
	private Optional<CurrentResult> layoutLeaderSegment(LeaderSegment ls) {
		try {
			if (leaderManager.hasLeader()) {
				return layoutLeader();
			}
			return Optional.empty();
		} finally {
			leaderManager.addLeader(ls);
		}
	}

	private Optional<CurrentResult> layoutPageSegment(PageNumberReferenceSegment rs) {
		Integer page = null;
		if (refs!=null) {
			page = refs.getPageNumber(rs.getRefId());
		}
		//TODO: translate references using custom language?
		Translatable spec;
		if (page==null) {
			spec = Translatable.text("??").locale(null).build();
		} else {
			String txt = "" + rs.getNumeralStyle().format(page);
			spec = Translatable.text(
					spc.getFormatterContext().getConfiguration().isMarkingCapitalLetters()?txt:txt.toLowerCase()
					).locale(null).attributes(rs.getTextAttribute(txt.length())).build();
		}
		if (leaderManager.hasLeader()) {
			layoutAfterLeader(spec, null);
		} else {
			String mode = null;
			BrailleTranslatorResult btr = toResult(spec, null);
			CurrentResult cr = new CurrentResultImpl(spc, btr, mode);
			return Optional.of(cr);
		}
		return Optional.empty();
	}
	
	private Optional<CurrentResult> layoutEvaluate(Evaluate e) {
		String txt = e.getExpression().render(context);
		if (!txt.isEmpty()) { // Don't create a new row if the evaluated expression is empty
		                    // Note: this could be handled more generally (also for regular text) in layout().
			Translatable spec = Translatable.text(spc.getFormatterContext().getConfiguration().isMarkingCapitalLetters()?txt:txt.toLowerCase()).
					locale(e.getTextProperties().getLocale()).
					hyphenate(e.getTextProperties().isHyphenating()).
					attributes(e.getTextAttribute(txt.length())).
					build();
			if (leaderManager.hasLeader()) {
				layoutAfterLeader(spec, null);
			} else {
				String mode = null;
				BrailleTranslatorResult btr = toResult(spec, mode);
				CurrentResult cr = new CurrentResultImpl(spc, btr, mode);
				return Optional.of(cr);
			}
		}
		return Optional.empty(); 
	}

	private void layoutAfterLeader(Translatable spec, String mode) {
		if (leaderManager.hasLeader()) {
			if (layoutOrApplyAfterLeader == null) {
				layoutOrApplyAfterLeader = new AggregatedBrailleTranslatorResult.Builder();
				// use the mode of the first following segment to translate the leader pattern (or
				// the mode of the first preceding segment)
				if (!seenSegmentAfterLeader) {
					currentLeaderMode = mode;
					seenSegmentAfterLeader = true;
				}
			}
			try {
				layoutOrApplyAfterLeader.add(spc.getFormatterContext().getTranslator(mode).translate(spec));
			} catch (TranslationException e) {
				throw new RuntimeException(e);
			}
		} else {
			throw new RuntimeException("Error in code.");
		}
	}
	
	private void applyAfterLeader(MarkerSegment marker) {
		if (leaderManager.hasLeader()) {
			if (layoutOrApplyAfterLeader == null) {
				layoutOrApplyAfterLeader = new AggregatedBrailleTranslatorResult.Builder();
			}
			layoutOrApplyAfterLeader.add(marker);
		} else {
			if (currentRow==null) {
				groupMarkers.add(marker);
			} else {
				currentRow.addMarker(marker);
			}
		}
	}
	
	private void applyAfterLeader(final AnchorSegment anchor) {
		if (leaderManager.hasLeader()) {
			if (layoutOrApplyAfterLeader == null) {
				layoutOrApplyAfterLeader = new AggregatedBrailleTranslatorResult.Builder();
			}
			layoutOrApplyAfterLeader.add(anchor);
		} else {
			if (currentRow==null) {
				groupAnchors.add(anchor.getReferenceID());
			} else {
				currentRow.addAnchor(anchor.getReferenceID());
			}
		}
	}
	
	private Optional<CurrentResult> layoutLeader() {
		if (leaderManager.hasLeader()) {
			// layout() sets currentLeader to null
			BrailleTranslatorResult btr;
			String mode;
			if (layoutOrApplyAfterLeader == null) {
				btr = toResult("");
				mode = null;
			} else {
				btr = layoutOrApplyAfterLeader.build();
				mode = currentLeaderMode;
				
				layoutOrApplyAfterLeader = null;
				seenSegmentAfterLeader = false;
			}
			CurrentResult cr = new CurrentResultImpl(spc, btr, mode);
			return Optional.of(cr);
		}
		return Optional.empty();
	}

	private BrailleTranslatorResult toResult(String c) {
		return toResult(Translatable.text(spc.getFormatterContext().getConfiguration().isMarkingCapitalLetters()?c:c.toLowerCase()).build(), null);
	}
	
	private BrailleTranslatorResult toResult(Translatable spec, String mode) {
		try {
			return spc.getFormatterContext().getTranslator(mode).translate(spec);
		} catch (TranslationException e) {
			throw new RuntimeException(e);
		}		
	}
	
	private interface CurrentResult {
		boolean hasNext(SegmentProcessing spi);
		Optional<RowImpl> process(SegmentProcessing spi);
		CurrentResult copy();
	}
	
	private static class CurrentResultImpl implements CurrentResult {
		private final SegmentProcessorContext spc;
		private final BrailleTranslatorResult btr;
		private final String mode;
		private boolean first;
		
		CurrentResultImpl(SegmentProcessorContext spc, BrailleTranslatorResult btr, String mode) {
			this.spc = spc;
			this.btr = btr;
			this.mode = mode;
			this.first = true;
		}
		
		CurrentResultImpl(CurrentResultImpl template) {
			this.spc = template.spc;
			this.btr = template.btr.copy();
			this.mode = template.mode;
			this.first = template.first;
		}

		@Override
		public CurrentResult copy() {
			return new CurrentResultImpl(this);
		}

		@Override
		public boolean hasNext(SegmentProcessing spi) {
			return first || btr.hasNext();
		}

		@Override
		public Optional<RowImpl> process(SegmentProcessing spi) {
			if (first) {
				first = false;
				return processFirst(spi);
			}
			try {
				if (btr.hasNext()) { //LayoutTools.length(chars.toString())>0
					if (spi.hasCurrentRow()) {
						return Optional.of(spi.flushCurrentRow());
					}
					return startNewRow(spi, btr, "", spc.getRdp().getTextIndent(), spc.getRdp().getBlockIndent(), mode);
				}
			} finally {
				if (!btr.hasNext() && btr.supportsMetric(BrailleTranslatorResult.METRIC_FORCED_BREAK)) {
					spi.addToForceCount(btr.getMetric(BrailleTranslatorResult.METRIC_FORCED_BREAK));
				}
			}
			return Optional.empty();
		}
	
		private Optional<RowImpl> processFirst(SegmentProcessing spi) {
			// process first row, is it a new block or should we continue the current row?
			if (!spi.hasCurrentRow()) {
				// add to left margin
				if (spi.hasListItem()) { //currentListType!=BlockProperties.ListType.NONE) {
					ListItem item = spi.getListItem();
					String listLabel;
					try {
						listLabel = spc.getFormatterContext().getTranslator(mode).translate(Translatable.text(spc.getFormatterContext().getConfiguration().isMarkingCapitalLetters()?item.getLabel():item.getLabel().toLowerCase()).build()).getTranslatedRemainder();
					} catch (TranslationException e) {
						throw new RuntimeException(e);
					}
					try {
						if (item.getType()==FormattingTypes.ListStyle.PL) {
							return startNewRow(spi, btr, listLabel, 0, spc.getRdp().getBlockIndentParent(), mode);
						} else {
							return startNewRow(spi, btr, listLabel, spc.getRdp().getFirstLineIndent(), spc.getRdp().getBlockIndent(), mode);
						}
					} finally {
						spi.discardListItem();
					}
				} else {
					return startNewRow(spi, btr, "", spc.getRdp().getFirstLineIndent(), spc.getRdp().getBlockIndent(), mode);
				}
			} else {
				return continueRow(spi, new RowInfo("", spc.getAvailable()), btr, spc.getRdp().getBlockIndent(), mode);
			}
		}
		
		private Optional<RowImpl> startNewRow(SegmentProcessing spi, BrailleTranslatorResult chars, String contentBefore, int indent, int blockIndent, String mode) {
			if (spi.hasCurrentRow()) {
				throw new RuntimeException("Error in code.");
			}
			spi.newCurrentRow(spc.getMargins().getLeftMargin(), spc.getMargins().getRightMargin());
			return continueRow(spi, new RowInfo(getPreText(contentBefore, indent+blockIndent), spc.getAvailable()), chars, blockIndent, mode);
		}
		
		private String getPreText(String contentBefore, int totalIndent) {
			int thisIndent = Math.max(
					// There is one known cause for this calculation to become < 0. That is when an ordered list is so long
					// that the number takes up more space than the indent reserved for it.
					// In that case it is probably best to push the content instead of failing altogether.
					totalIndent - StringTools.length(contentBefore),
					0);
			return contentBefore + StringTools.fill(spc.getSpaceCharacter(), thisIndent);
		}
	
		//TODO: check leader functionality
		private Optional<RowImpl> continueRow(SegmentProcessing spi, RowInfo m1, BrailleTranslatorResult btr, int blockIndent, String mode) {
			RowImpl ret = null;
			// [margin][preContent][preTabText][tab][postTabText] 
			//      preContentPos ^
			String tabSpace = "";
			if (spi.getLeaderManager().hasLeader()) {
				int preTabPos = m1.getPreTabPosition(spi.getCurrentRow());
				int leaderPos = spi.getLeaderManager().getLeaderPosition(spc.getAvailable());
				int offset = leaderPos-preTabPos;
				int align = spi.getLeaderManager().getLeaderAlign(btr.countRemaining());
				
				if (preTabPos>leaderPos || offset - align < 0) { // if tab position has been passed or if text does not fit within row, try on a new row
					MarginProperties _leftMargin = spi.getCurrentRow().getLeftMargin();
					if (spi.hasCurrentRow()) {
						ret = spi.flushCurrentRow();
					}
					spi.newCurrentRow(_leftMargin, spc.getMargins().getRightMargin());
					m1 = new RowInfo(getPreText("", spc.getRdp().getTextIndent()+blockIndent), spc.getAvailable());
					//update offset
					offset = leaderPos-m1.getPreTabPosition(spi.getCurrentRow());
				}
				try {
					tabSpace = spi.getLeaderManager().getLeaderPattern(spc.getFormatterContext().getTranslator(mode), offset - align);
				} finally {
					// always discard leader
					spi.getLeaderManager().removeLeader();
				}
			}
			breakNextRow(m1, spi.getCurrentRow(), btr, tabSpace);
			return Optional.ofNullable(ret);
		}
	
		private void breakNextRow(RowInfo m1, RowImpl.Builder row, BrailleTranslatorResult btr, String tabSpace) {
			int contentLen = StringTools.length(tabSpace) + StringTools.length(row.getText());
			boolean force = contentLen == 0;
			//don't know if soft hyphens need to be replaced, but we'll keep it for now
			String next = softHyphenPattern.matcher(btr.nextTranslatedRow(m1.getMaxLength(row) - contentLen, force)).replaceAll("");
			if ("".equals(next) && "".equals(tabSpace)) {
				row.text(m1.getPreContent() + trailingWsBraillePattern.matcher(row.getText()).replaceAll(""));
			} else {
				row.text(m1.getPreContent() + row.getText() + tabSpace + next);
				row.leaderSpace(row.getLeaderSpace()+tabSpace.length());
			}
			if (btr instanceof AggregatedBrailleTranslatorResult) {
				AggregatedBrailleTranslatorResult abtr = ((AggregatedBrailleTranslatorResult)btr);
				row.addMarkers(abtr.getMarkers());
				row.addAnchors(abtr.getAnchors());
				abtr.clearPending();
			}
		}
	}
	
	void reset() {
		groupAnchors.clear();
		groupMarkers.clear();
		initFields();
	}
	
	List<Marker> getGroupMarkers() {
		return groupMarkers;
	}
	
	List<String> getGroupAnchors() {
		return groupAnchors;
	}
	
	void setContext(DefaultContext context) {
		this.context = context;
	}
	
	int getForceCount() {
		return forceCount;
	}

	@Override
	public boolean isEmpty() {
		return empty;
	}

	@Override
	public boolean hasCurrentRow() {
		return currentRow!=null;
	}

	@Override
	public int getUnusedLeft() {
		return minLeft;
	}

	@Override
	public int getUnusedRight() {
		return minRight;
	}

	@Override
	public void newCurrentRow(MarginProperties left, MarginProperties right) {
		currentRow = spc.getRdp().configureNewEmptyRowBuilder(left, right);
	}

	@Override
	public Builder getCurrentRow() {
		return currentRow;
	}

	@Override
	public void addToForceCount(double value) {
		forceCount += value;
	}

	@Override
	public LeaderManager getLeaderManager() {
		return leaderManager;
	}

	@Override
	public boolean hasListItem() {
		return item!=null;
	}

	@Override
	public void discardListItem() {
		item = null;
	}

	@Override
	public ListItem getListItem() {
		Objects.requireNonNull(item);
		return item;
	}
}