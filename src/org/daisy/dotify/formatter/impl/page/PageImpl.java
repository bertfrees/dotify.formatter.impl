package org.daisy.dotify.formatter.impl.page;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.daisy.dotify.api.formatter.Marker;
import org.daisy.dotify.api.formatter.PageAreaProperties;
import org.daisy.dotify.api.translator.BrailleTranslator;
import org.daisy.dotify.api.writer.Row;
import org.daisy.dotify.formatter.impl.core.BorderManager;
import org.daisy.dotify.formatter.impl.core.FormatterContext;
import org.daisy.dotify.formatter.impl.core.HeightCalculator;
import org.daisy.dotify.formatter.impl.core.LayoutMaster;
import org.daisy.dotify.formatter.impl.core.PageTemplate;
import org.daisy.dotify.formatter.impl.core.PaginatorException;
import org.daisy.dotify.formatter.impl.row.RowImpl;
import org.daisy.dotify.formatter.impl.search.PageDetails;
import org.daisy.dotify.writer.impl.Page;


//FIXME: scope spread is currently implemented using document wide scope, i.e. across volume boundaries. This is wrong, but is better than the previous sequence scope.
/**
 * Provides a page object.
 * 
 * @author Joel Håkansson
 */
public class PageImpl implements Page {
	private final FieldResolver fieldResolver;
	private final PageDetails details;
	private final LayoutMaster master;
	private final FormatterContext fcontext;
	private final List<RowImpl> before;
	private final List<RowImpl> after;
    private final ArrayList<RowImpl> pageArea;
    private final ArrayList<String> anchors;
    private final ArrayList<String> identifiers;
	private final int pagenum;
	private final int flowHeight;
	private final PageTemplate template;
	private final int pageMargin;
	private final PageTemplate pageTemplate;
	private final BorderManager finalRows;

	private boolean hasRows;
	private boolean isVolBreakAllowed;
	private int keepPreviousSheets;
	private Integer volumeBreakAfterPriority;
	private final BrailleTranslator filter;
	
	public PageImpl(FieldResolver fieldResolver, PageDetails details, LayoutMaster master, FormatterContext fcontext, List<RowImpl> before, List<RowImpl> after) {
		this.fieldResolver = fieldResolver;
		this.details = details;
		this.master = master;
		this.fcontext = fcontext;
		this.before = before;
		this.after = after;

		this.pageArea = new ArrayList<>();
		this.anchors = new ArrayList<>();
		this.identifiers = new ArrayList<>();
		this.pagenum = details.getPageNumberIndex() + 1;
		this.template = master.getTemplate(pagenum);
        this.flowHeight = master.getFlowHeight(template);
		this.isVolBreakAllowed = true;
		this.keepPreviousSheets = 0;
		this.volumeBreakAfterPriority = null;
		this.pageMargin = ((pagenum % 2 == 0) ? master.getOuterMargin() : master.getInnerMargin());
		this.pageTemplate = master.getTemplate(pagenum);
		this.finalRows = new BorderManager(master, fcontext, pageMargin);
		this.hasRows = false;
		this.filter = fcontext.getDefaultTranslator();
	}
	
	public PageImpl(PageImpl template) {
		this.fieldResolver = template.fieldResolver;
		this.details = template.details;
		this.master = template.master;
		this.fcontext = template.fcontext;
		this.before = new ArrayList<>(template.before);
		this.after = new ArrayList<>(template.after);
	    this.pageArea = new ArrayList<>(template.pageArea);
	    this.anchors = new ArrayList<>(template.anchors);
	    this.identifiers = new ArrayList<>(template.identifiers);
		this.pagenum = template.pagenum;
		this.flowHeight = template.flowHeight;
		this.template = template.template;
		this.pageMargin = template.pageMargin;
		this.pageTemplate = template.pageTemplate;
		this.finalRows = new BorderManager(template.finalRows);

		this.hasRows = template.hasRows;
		this.isVolBreakAllowed = template.isVolBreakAllowed;
		this.keepPreviousSheets = template.keepPreviousSheets;
		this.volumeBreakAfterPriority = template.volumeBreakAfterPriority;
		this.filter = template.filter;
	}
	
	public static PageImpl copyUnlessNull(PageImpl page) {
		return page==null?null:new PageImpl(page);
	}

	void addToPageArea(List<RowImpl> block) {
		if (hasRows) {
			throw new IllegalStateException("Page area must be added before adding rows.");
		}
		pageArea.addAll(block);
	}
	
	void newRow(RowImpl r) {
		if (!hasRows) {
			//add the header
	        finalRows.addAll(fieldResolver.renderFields(getDetails(), pageTemplate.getHeader(), filter));
	        //add the top page area
			addTopPageArea();
			getDetails().startsContentMarkers();
			hasRows = true;
		}
		finalRows.addRow(r);
		getDetails().getMarkers().addAll(r.getMarkers());
		anchors.addAll(r.getAnchors());
	}
	
	void addMarkers(List<Marker> m) {
		getDetails().getMarkers().addAll(m);
	}
	
	public List<String> getAnchors() {
		return anchors;
	}
	
	void addIdentifier(String id) {
		identifiers.add(id);
	}
	
	public List<String> getIdentifiers() {
		return identifiers;
	}
	
	/**
	 * Gets the page space needed to render the rows. 
	 * @param rows
	 * @param defSpacing a value >= 1.0
	 * @return returns the space, in rows
	 */
	static float rowsNeeded(Collection<? extends Row> rows, float defSpacing) {
		HeightCalculator c = new HeightCalculator(defSpacing);
		c.addRows(rows);
		return c.getCurrentHeight();
	}

	private float spaceNeeded() {
		return 	pageAreaSpaceNeeded() +
				finalRows.getOffsetHeight();
	}
	
	float staticAreaSpaceNeeded() {
		return rowsNeeded(before, master.getRowSpacing()) + rowsNeeded(after, master.getRowSpacing());
	}
	
	float pageAreaSpaceNeeded() {
		return (!pageArea.isEmpty() ? staticAreaSpaceNeeded() + rowsNeeded(pageArea, master.getRowSpacing()) : 0);
	}
	
	int spaceUsedOnPage(int offs) {
		return (int)Math.ceil(spaceNeeded()) + offs;
	}
	
	private void addTopPageArea() {
        if (master.getPageArea()!=null && master.getPageArea().getAlignment()==PageAreaProperties.Alignment.TOP && !pageArea.isEmpty()) {
			finalRows.addAll(before);
			finalRows.addAll(pageArea);
			finalRows.addAll(after);
		}
	}

	/*
	 * The assumption is made that by now all pages have been added to the parent sequence and volume scopes
	 * have been set on the page struct.
	 */
	@Override
	public List<Row> getRows() {
		try {
			if (!finalRows.isClosed()) {
				if (!hasRows) { // the header hasn't been added yet 
					//add the header
			        finalRows.addAll(fieldResolver.renderFields(getDetails(), pageTemplate.getHeader(), filter));
			      //add top page area
					addTopPageArea();
				}
		        float headerHeight = pageTemplate.getHeaderHeight();
		        if (!pageTemplate.getFooter().isEmpty() || finalRows.hasBorder() || (master.getPageArea()!=null && master.getPageArea().getAlignment()==PageAreaProperties.Alignment.BOTTOM && !pageArea.isEmpty())) {
		            float areaSize = (master.getPageArea()!=null && master.getPageArea().getAlignment()==PageAreaProperties.Alignment.BOTTOM ? pageAreaSpaceNeeded() : 0);
		            while (Math.ceil(finalRows.getOffsetHeight() + areaSize) < getFlowHeight() + headerHeight) {
						finalRows.addRow(new RowImpl());
					}
					if (master.getPageArea()!=null && master.getPageArea().getAlignment()==PageAreaProperties.Alignment.BOTTOM && !pageArea.isEmpty()) {
						finalRows.addAll(before);
						finalRows.addAll(pageArea);
						finalRows.addAll(after);
					}
		            finalRows.addAll(fieldResolver.renderFields(getDetails(), pageTemplate.getFooter(), filter));
				}
			}
			return finalRows.getRows();
		} catch (PaginatorException e) {
			throw new RuntimeException("Pagination failed.", e);
		}
	}

	/**
	 * Get the page index, offset included, zero based. Don't assume
	 * getPageIndex() % 2 == getPageOrdinal() % 2
	 * 
	 * @return returns the page index in the sequence (zero based)
	 */
	public int getPageIndex() {
		return details.getPageNumberIndex();
	}

	/**
	 * Gets the flow height for this page, i.e. the number of rows available for text flow
	 * @return returns the flow height
	 */
	int getFlowHeight() {
		return flowHeight;
	}
	
	void setKeepWithPreviousSheets(int value) {
		keepPreviousSheets = Math.max(value, keepPreviousSheets);
	}
	
	void setAllowsVolumeBreak(boolean value) {
		this.isVolBreakAllowed = value;
	}

	public boolean allowsVolumeBreak() {
		return isVolBreakAllowed;
	}

	public int keepPreviousSheets() {
		return keepPreviousSheets;
	}

	PageTemplate getPageTemplate() {
		return template;
	}
	
	public Integer getAvoidVolumeBreakAfter() {
		return volumeBreakAfterPriority;
	}
	
	void setAvoidVolumeBreakAfter(Integer value) {
		this.volumeBreakAfterPriority = value;
	}

	public PageDetails getDetails() {
		return details;
	}

}
