[![Build Status](https://travis-ci.com/braillespecs/obfl.svg?branch=master)](https://travis-ci.com/braillespecs/obfl)

Provides a complete layout specification for braille books

# Open Braille Formatting Language #

So far, Braille formatting has been done using braille editors. Braille editors can often import other formats, interpreting these formats on the fly, using proprietary rule systems (often tailored to a particular organization's needs). However, Braille translation is very different depending on language and region, and the technology used by different organizations varies greatly as well, making it difficult to share efforts or benefit from work that other people have done. In order to overcome these obstacles and improve the foundation for global braille software sharing, a formatting language for braille is needed.

The Open Braille Formatting Language is such a langauge. It is inspired by XSL-FO, where the formatting is described using a _specific XML-format_ with the sole purpose of describing a text's intended rendering. In other words, the Open Braille Formatting Language describes how a formatter should render a text, including margins, indentation, lists, tables and so on, but excluding _any additional file format processing_. Focusing on the task of formatting and leaving the interpretation of source data formats separate, allows for an more efficient formatter design and improves the changes of future braille software intercommunication.

Note: The Open Braille Formatting Language is currently a draft. Additions and modifications are expected.

The latest draft for the OBFL specification is available [here](https://braillespecs.github.io/obfl/obfl-specification.html).

# Related Systems Overview #
![https://docs.google.com/drawings/pub?id=1QoXC4R7sVz0zGCYv5ueRgyCKg6OkTsg0NROhkKMToGo&w=1031&h=432&dummy=.jpg](https://docs.google.com/drawings/pub?id=1QoXC4R7sVz0zGCYv5ueRgyCKg6OkTsg0NROhkKMToGo&w=1031&h=432&dummy=.jpg)
